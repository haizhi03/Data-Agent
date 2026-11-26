#ifndef PackageDir
  #error PackageDir must point at the packaged Windows directory
#endif
#ifndef OutputDir
  #error OutputDir is required
#endif
#ifndef AppVersion
  #define AppVersion "0.1.0"
#endif

[Setup]
AppId={{61593A94-2B36-49F8-AF34-7BAA1C6024DE}
AppName=Data Agent
AppVersion={#AppVersion}
DefaultDirName={localappdata}\Programs\Data Agent
DefaultGroupName=Data Agent
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
AppMutex=DataAgent.Desktop
CloseApplications=yes
RestartApplications=no
OutputDir={#OutputDir}
OutputBaseFilename=DataAgent-windows-x64-setup
Compression=lzma2/fast
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\DataAgent.exe

[Files]
Source: "{#PackageDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Data Agent"; Filename: "{app}\DataAgent.exe"
Name: "{autodesktop}\Data Agent"; Filename: "{app}\DataAgent.exe"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; Flags: unchecked

[Run]
Filename: "{app}\DataAgent.exe"; Description: "Open Data Agent"; Flags: nowait postinstall skipifsilent

; User data lives in the user's .data-agent directory, outside {app}.
; Deliberately no [UninstallDelete] entries: uninstall/upgrade must preserve credentials.
