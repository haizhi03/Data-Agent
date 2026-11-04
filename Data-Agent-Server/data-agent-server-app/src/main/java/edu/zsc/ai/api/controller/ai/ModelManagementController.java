package edu.zsc.ai.api.controller.ai;

import edu.zsc.ai.config.ai.model.ModelPurpose;
import edu.zsc.ai.domain.model.dto.request.ai.ModelManagementRequests;
import edu.zsc.ai.domain.model.dto.response.ai.ModelManagementResponses.*;
import edu.zsc.ai.domain.model.dto.response.base.ApiResponse;
import edu.zsc.ai.domain.service.ai.model.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class ModelManagementController {
    private final ModelManagementService service;
    private final ModelDiagnosticService diagnostics;
    private final ModelConnectionTestService connectionTests;
    @GetMapping("/model-management") public ApiResponse<Status> status(){return ApiResponse.success(service.status());}
    @GetMapping("/model-profiles") public ApiResponse<List<Profile>> profiles(){service.scope(false);return ApiResponse.success(ModelProfiles.all());}
    @GetMapping("/model-specifications") public ApiResponse<List<edu.zsc.ai.config.ai.model.QwenModelSpecifications.Specification>> specifications(){service.scope(false);return ApiResponse.success(edu.zsc.ai.config.ai.model.QwenModelSpecifications.all());}
    @GetMapping("/model-connections") public ApiResponse<List<Connection>> connections(){return ApiResponse.success(service.connections());}
    @GetMapping("/model-connections/{id}") public ApiResponse<Connection> connection(@PathVariable String id){return ApiResponse.success(service.connection(id));}
    @PostMapping("/model-connections") public ApiResponse<Connection> createConnection(@RequestBody ModelManagementRequests.Connection input){return ApiResponse.success(service.createConnection(input));}
    @PostMapping("/model-setups") public ApiResponse<Setup> saveSetup(@RequestBody ModelManagementRequests.Setup input){return ApiResponse.success(service.saveSetup(input));}
    @PostMapping("/model-connections/test") public ApiResponse<ConnectionTest> testConnection(@RequestBody ModelManagementRequests.ConnectionTest input){return ApiResponse.success(connectionTests.test(input));}
    @PatchMapping("/model-connections/{id}") public ApiResponse<Connection> updateConnection(@PathVariable String id,@RequestBody ModelManagementRequests.Connection input){return ApiResponse.success(service.updateConnection(id,input));}
    @PostMapping("/model-connections/{id}/credential") public ApiResponse<Connection> credential(@PathVariable String id,@RequestBody ModelManagementRequests.Credential input){return ApiResponse.success(service.credential(id,input));}
    @PostMapping("/model-connections/{id}/disable") public ApiResponse<Connection> disableConnection(@PathVariable String id,@RequestBody ModelManagementRequests.Version input){return ApiResponse.success(service.disableConnection(id,input));}
    @GetMapping("/model-configs") public ApiResponse<List<Model>> models(){return ApiResponse.success(service.models());}
    @GetMapping("/model-configs/{id}") public ApiResponse<Model> model(@PathVariable String id){return ApiResponse.success(service.model(id));}
    @PostMapping("/model-configs") public ApiResponse<Model> createModel(@RequestBody ModelManagementRequests.Model input){return ApiResponse.success(service.createModel(input));}
    @PatchMapping("/model-configs/{id}") public ApiResponse<Model> updateModel(@PathVariable String id,@RequestBody ModelManagementRequests.Model input){return ApiResponse.success(service.updateModel(id,input));}
    @PostMapping("/model-configs/{id}/disable") public ApiResponse<Model> disableModel(@PathVariable String id,@RequestBody ModelManagementRequests.Version input){return ApiResponse.success(service.disableModel(id,input));}
    @GetMapping("/model-bindings") public ApiResponse<List<Binding>> bindings(){return ApiResponse.success(service.bindings());}
    @GetMapping("/model-bindings/{purpose}") public ApiResponse<Binding> binding(@PathVariable ModelPurpose purpose){return ApiResponse.success(service.bindings().stream().filter(b->b.purpose()==purpose).findFirst().orElseThrow());}
    @PutMapping("/model-bindings/{purpose}") public ApiResponse<Binding> bind(@PathVariable ModelPurpose purpose,@RequestBody ModelManagementRequests.Binding input){return ApiResponse.success(service.bind(purpose,input));}
    @PutMapping("/model-bindings/{purpose}/preset") public ApiResponse<Binding> bindPreset(@PathVariable ModelPurpose purpose,@RequestBody ModelManagementRequests.PresetBinding input){return ApiResponse.success(service.bindPreset(purpose,input));}
    @PostMapping("/model-configs/{id}/test") public ApiResponse<Test> test(@PathVariable String id,@RequestBody ModelManagementRequests.Test input){return ApiResponse.success(diagnostics.start(id,input.level()));}
    @GetMapping("/model-configs/{id}/tests") public ApiResponse<List<Test>> tests(@PathVariable String id){return ApiResponse.success(diagnostics.latest(id));}
    @GetMapping("/model-tests/{id}") public ApiResponse<Test> test(@PathVariable String id){return ApiResponse.success(diagnostics.read(id));}
}
