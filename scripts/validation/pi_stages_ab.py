#!/usr/bin/env python3
"""Run repeatable A/B contract checks and emit evidence without promoting release status."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
SERVER = ROOT / "Data-Agent-Server"
APP = SERVER / "data-agent-server-app"
WORKER = ROOT / "Data-Agent-Pi-Worker"
CLIENT = ROOT / "Data-Agent-Client"


def command_output(command):
    return subprocess.check_output(command, cwd=ROOT, text=True, stderr=subprocess.STDOUT).strip()


def test_cases(paths):
    cases = []
    for path in paths:
        for case in ET.parse(path).iter("testcase"):
            status = "PASS"
            if case.find("skipped") is not None:
                status = "SKIPPED"
            if case.find("failure") is not None or case.find("error") is not None:
                status = "FAIL"
            cases.append({"class": case.get("classname", ""), "name": case.get("name", ""), "status": status})
    return cases


def score_tasks(tasks, cases):
    scored = []
    for task in tasks:
        matches = [case for case in cases if case["class"].endswith("." + task["testClass"]) and case["name"] == task["testMethod"]]
        scored.append({**task, "status": "PASS" if matches and all(case["status"] == "PASS" for case in matches) else "FAIL"})
    return scored


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    live = parser.add_mutually_exclusive_group()
    live.add_argument("--live-mysql", action="store_true", help="Call the configured real models and existing sys_demo MySQL")
    live.add_argument("--live-dm", action="store_true", help="Call real models against a configured DM Schema and test table")
    args = parser.parse_args()
    if (args.live_mysql or args.live_dm) and not os.environ.get("DATA_AGENT_PI_LOCAL_DIRECTORY"):
        parser.error("Live validation requires DATA_AGENT_PI_LOCAL_DIRECTORY")
    if args.live_dm:
        required = ["DATA_AGENT_PI_DM_CONNECTION_ID", "DATA_AGENT_PI_DM_SCHEMA", "DATA_AGENT_PI_DM_TABLE"]
        missing = [name for name in required if not os.environ.get(name)]
        if missing:
            parser.error("Missing DM settings: " + ", ".join(missing))
    output = APP / "target" / ("pi-ab-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S-%f"))
    output.mkdir(parents=True)
    corpus_path = ROOT / "docs/pi-validation/readonly-corpus.json"
    corpus = json.loads(corpus_path.read_text())
    sources = [path for directory in [WORKER / "src", WORKER / "durable-prototype/src"]
               for path in directory.glob("*.js")]
    sources.extend([
        CLIENT / "src/components/ai/LocalAssistant.tsx",
        CLIENT / "src/components/ai/LocalAssistant.context.test.tsx",
        APP / "src/main/resources/prompt/pi-main-agent_zh.md",
        APP / "src/main/java/edu/zsc/ai/local/LocalPiChatService.java",
        APP / "src/test/java/edu/zsc/ai/local/LocalPiMySqlLiveTest.java",
        WORKER / "package-lock.json", WORKER / "durable-prototype/package-lock.json",
    ])
    report = {
        "verifiedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "commit": command_output(["git", "rev-parse", "HEAD"]),
        "workingTree": command_output(["git", "status", "--short"]),
        "corpusSha256": hashlib.sha256(corpus_path.read_bytes()).hexdigest(),
        "sourceSha256": {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                         for path in sorted(sources)},
        "versions": {name: command_output(cmd) for name, cmd in {
            "node": ["node", "--version"], "java": ["java", "-version"], "maven": ["mvn", "-v"],
        }.items()},
        "suites": [], "tasks": [],
        "liveTasks": [{**task, "status": "NOT_RUN"} for task in corpus["liveTasks"]],
        "dmTasks": [{**task, "status": "NOT_RUN"} for task in corpus["dmTasks"]],
        "frameworkDecision": "continue-core",
        "releaseStatus": "PENDING_REAL_DATABASE_AND_COMPARATIVE_ACCEPTANCE",
        "releaseEvidenceRequired": corpus["releaseEvidenceRequired"],
    }

    def run(name, cwd, command, xml_paths, env=None):
        print(f"Running {name}", flush=True)
        started = time.time()
        with (output / (name + ".log")).open("w") as log:
            result = subprocess.run(command, cwd=cwd, stdout=log, stderr=subprocess.STDOUT, env=env)
        # Old Surefire files cannot count as evidence for this invocation.
        fresh = [path for path in xml_paths() if path.stat().st_mtime >= started - 1]
        cases = test_cases(fresh)
        suite = {"name": name, "exitCode": result.returncode, "elapsedSeconds": round(time.time() - started, 3),
                 "tests": len(cases), "passed": sum(case["status"] == "PASS" for case in cases),
                 "skipped": sum(case["status"] == "SKIPPED" for case in cases),
                 "status": "PASS" if result.returncode == 0 and any(case["status"] == "PASS" for case in cases) else "FAIL"}
        report["suites"].append(suite)
        return cases

    for name, directory in [("core", WORKER), ("durable", WORKER / "durable-prototype")]:
        xml = output / (name + ".xml")
        tests = sorted(str(path) for path in (directory / "test").glob("*.test.js"))
        run(name, directory, ["node", "--test", "--test-reporter=junit", "--test-reporter-destination=" + str(xml), *tests], lambda: [xml] if xml.exists() else [])
    cases = run("java-local", SERVER, ["mvn", "-pl", "data-agent-server-app", "-am", "test",
                "-Dtest=edu.zsc.ai.local.*Test", "-Dsurefire.failIfNoSpecifiedTests=false"],
                lambda: list((APP / "target/surefire-reports").glob("TEST-edu.zsc.ai.local.*.xml")),
                {**os.environ, "DATA_AGENT_PI_MYSQL_LIVE": "false", "DATA_AGENT_PI_COMPARE_LIVE": "false", "DATA_AGENT_PI_DM_LIVE": "false"})
    report["tasks"] = score_tasks(corpus["tasks"], cases)
    xml = output / "frontend.xml"
    run("frontend", CLIENT, ["npm", "test", "--", "src/components/ai/LocalAssistant.context.test.tsx",
        "src/services/localAssistant.service.test.ts", "src/lib/localRuntime.test.ts", "--maxWorkers=1",
        "--reporter=junit", "--outputFile=" + str(xml)], lambda: [xml] if xml.exists() else [])
    if args.live_mysql or args.live_dm:
        dataset = "dm" if args.live_dm else "mysql"
        task_key = "dmTasks" if args.live_dm else "liveTasks"
        env = {**os.environ, "DATA_AGENT_PI_MYSQL_LIVE": str(args.live_mysql).lower(),
               "DATA_AGENT_PI_COMPARE_LIVE": str(args.live_mysql).lower(), "DATA_AGENT_PI_DM_LIVE": str(args.live_dm).lower()}
        live_report = APP / "target" / ("pi-dm-live-summary.json" if args.live_dm else "pi-readonly-comparison.json")
        # Preserve this run's exact evidence alongside its logs and test results.
        started = time.time()
        cases = run(dataset + "-live", SERVER, ["mvn", "-pl", "data-agent-server-app", "-am", "test",
            "-Dtest=LocalPiMySqlLiveTest", "-Dsurefire.failIfNoSpecifiedTests=false"],
            lambda: list((APP / "target/surefire-reports").glob("TEST-*LocalPiMySqlLiveTest.xml")), env)
        report[task_key] = score_tasks(corpus[task_key], cases)
        if live_report.exists() and live_report.stat().st_mtime >= started - 1:
            report[dataset + "Live"] = json.loads(live_report.read_text())
    passed = all(item["status"] == "PASS" for item in report["suites"] + report["tasks"])
    if args.live_mysql or args.live_dm:
        passed = passed and all(item["status"] == "PASS" for item in report[task_key])
    report["contractStatus"] = "PASS" if passed else "FAIL"
    (output / "summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(f"{report['contractStatus']}. Evidence saved to {output / 'summary.json'}")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
