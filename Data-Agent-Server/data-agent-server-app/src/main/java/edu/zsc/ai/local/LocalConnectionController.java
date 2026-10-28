package edu.zsc.ai.local;

import edu.zsc.ai.domain.model.dto.response.base.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/connections")
final class LocalConnectionController {
    private final LocalConnectionService service;
    LocalConnectionController(LocalConnectionService service) { this.service = service; }
    @GetMapping ApiResponse<List<LocalConnectionRecord.PublicView>> list() { return ApiResponse.success(service.list()); }
    @GetMapping("/{id}") ApiResponse<?> get(@PathVariable long id) { return ApiResponse.success(service.get(id)); }
    @PostMapping("/create") ApiResponse<?> create(@Valid @RequestBody LocalConnectionInput input) { return ApiResponse.success(service.save(input, true)); }
    @PutMapping ApiResponse<?> update(@Valid @RequestBody LocalConnectionInput input) { return ApiResponse.success(service.save(input, false)); }
    @DeleteMapping("/{id}") ApiResponse<?> delete(@PathVariable long id, @RequestParam Long expectedVersion) {
        service.delete(id, expectedVersion); return ApiResponse.success();
    }
    @PostMapping("/test") ApiResponse<?> test(@Valid @RequestBody LocalConnectionTestInput request) { return ApiResponse.success(service.testConnection(request)); }
    @PostMapping("/open") ApiResponse<?> open(@RequestParam Long connectionId) { return ApiResponse.success(service.openConnection(connectionId)); }
    @DeleteMapping("/active/{id}") ApiResponse<?> close(@PathVariable Long id) { service.closeConnection(id); return ApiResponse.success(); }
}
