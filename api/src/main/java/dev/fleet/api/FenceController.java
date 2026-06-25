package dev.fleet.api;

import dev.fleet.api.db.FenceRow;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/tenants/{tenantId}/fences")
public class FenceController {
    private final FenceService fences;

    public FenceController(FenceService fences) {
        this.fences = fences;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FenceRow create(@PathVariable long tenantId, @RequestBody FenceRequest request) {
        return fences.create(tenantId, request);
    }

    @PutMapping("/{id}")
    public FenceRow update(
            @PathVariable long tenantId, @PathVariable long id, @RequestBody FenceRequest request) {
        return fences.update(tenantId, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long tenantId, @PathVariable long id) {
        fences.delete(tenantId, id);
    }
}
