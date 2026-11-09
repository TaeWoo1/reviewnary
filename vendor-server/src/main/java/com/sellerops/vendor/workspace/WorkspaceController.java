package com.sellerops.vendor.workspace;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the workspace server asks naming no organisation: which of its organisations an operator linked here. It pulls
 * and watches those, each with the organisation named ({@link WorkspaceLinks#ORG_HEADER}).
 */
@RestController
public class WorkspaceController {

    public record Organisations(List<UUID> organisations) {}

    private final WorkspaceLinks links;

    public WorkspaceController(WorkspaceLinks links) {
        this.links = links;
    }

    @GetMapping("/v1/workspace/organisations")
    public Organisations organisations() {
        return new Organisations(links.organisations());
    }
}
