package com.example.messaging.web;

import com.example.messaging.batch.BulkJobService;
import com.example.messaging.common.PageResponse;
import com.example.messaging.domain.Category;
import com.example.messaging.domain.Channel;
import com.example.messaging.web.dto.BulkJobDtos.BulkJobFailureView;
import com.example.messaging.web.dto.BulkJobDtos.BulkJobView;
import com.example.messaging.web.dto.BulkJobDtos.GenerateSyntheticRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Bulk send jobs (CONTRACT 5 / OpenAPI "Bulk jobs"). Creating, stopping and restarting need role SENDER, TENANT_ADMIN
 * or PLATFORM_ADMIN <b>and</b> scope {@code messages:bulk}; that check lives in {@link BulkJobService} so a refusal
 * is audited. Reads are open to the bulk roles plus OPERATOR and AUDITOR and are tenant scoped.
 */
@RestController
@RequestMapping(value = "/api/v1/bulk-jobs", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Bulk jobs")
public class BulkJobController {

    private static final String READ_ROLES = "hasAnyRole('SENDER','TENANT_ADMIN','PLATFORM_ADMIN','OPERATOR','AUDITOR')";

    private final BulkJobService service;

    public BulkJobController(BulkJobService service) {
        this.service = service;
    }

    @Operation(summary = "Upload a recipient CSV and start a bulk send")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BulkJobView> create(
            @RequestPart(name = "file", required = false) MultipartFile file,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String templateCode,
            @RequestParam(required = false) Channel channel,
            @RequestParam(required = false) Category category,
            @RequestParam(required = false) Integer ratePerSecond) {
        BulkJobView job = service.create(name, templateCode, channel, category, ratePerSecond, file);
        return ResponseEntity.accepted().location(URI.create("/api/v1/bulk-jobs/" + job.id())).body(job);
    }

    @Operation(summary = "Create and submit a synthetic job without upload",
            description = "Creates the needed SYN-nnnnnnn recipients first unless createRecipients=false.")
    @PostMapping(value = "/generate-synthetic", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BulkJobView> generateSynthetic(@Valid @RequestBody GenerateSyntheticRequest request,
            @RequestParam(defaultValue = "true") boolean createRecipients) {
        BulkJobView job = service.generateSynthetic(request, createRecipients);
        return ResponseEntity.accepted().location(URI.create("/api/v1/bulk-jobs/" + job.id())).body(job);
    }

    @GetMapping
    @PreAuthorize(READ_ROLES)
    public PageResponse<BulkJobView> list(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort) {
        return PageResponse.of(service.list(
                Pagination.of(page, size, sort, service.sortableJobs(), "createdAt", Sort.Direction.DESC)));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ_ROLES)
    public BulkJobView get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping("/{id}/failures")
    @PreAuthorize(READ_ROLES)
    public PageResponse<BulkJobFailureView> failures(@PathVariable String id,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return PageResponse.of(service.failures(id,
                Pagination.of(page, size, sort, service.sortableFailures(), "lineNo", Sort.Direction.ASC)));
    }

    @PostMapping("/{id}/stop")
    public BulkJobView stop(@PathVariable String id) {
        return service.stop(id);
    }

    @PostMapping("/{id}/restart")
    public BulkJobView restart(@PathVariable String id) {
        return service.restart(id);
    }
}
