package com.sellerops.knowledge.foldersync;

import com.sellerops.auth.AuthPrincipal;
import com.sellerops.common.ApiException;
import com.sellerops.user.User;
import com.sellerops.user.UserRepository;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 자료 폴더 동기화. The org and the actor come only from the authenticated principal. */
@RestController
@RequestMapping("/api/knowledge/folder")
public class KnowledgeFolderController {

    private final KnowledgeFolderSyncService service;
    private final UserRepository users;

    public KnowledgeFolderController(KnowledgeFolderSyncService service, UserRepository users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping
    public KnowledgeFolderViews.Status status(@AuthenticationPrincipal AuthPrincipal p) {
        return service.status(p.orgId());
    }

    @PostMapping("/scan")
    public KnowledgeFolderViews.ScanResult scan(@AuthenticationPrincipal AuthPrincipal p,
                                                @RequestBody KnowledgeFolderViews.ScanRequest request) {
        return service.scan(p.orgId(), p.userId(), request);
    }

    @PostMapping(path = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public KnowledgeFolderViews.UploadResult upload(@AuthenticationPrincipal AuthPrincipal p,
                                                    @RequestParam String path,
                                                    @RequestParam(required = false) String sha256,
                                                    @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("파일이 비어 있습니다.");
        }
        try {
            return service.upload(p.orgId(), p.userId(), actorName(p), path, sha256, file.getBytes());
        } catch (IOException e) {
            throw ApiException.badRequest("파일을 읽지 못했습니다.");
        }
    }

    @PostMapping("/files/{id}/use-folder")
    public KnowledgeFolderViews.Status useFolder(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID id) {
        return service.useFolder(p.orgId(), id);
    }

    @DeleteMapping
    public KnowledgeFolderViews.Status unlink(@AuthenticationPrincipal AuthPrincipal p) {
        return service.unlink(p.orgId());
    }

    private String actorName(AuthPrincipal p) {
        String name = users.findById(p.userId()).map(User::getName).orElse(null);
        return name != null && !name.isBlank() ? name : "폴더 동기화";
    }
}
