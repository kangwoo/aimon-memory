package at.aimon.memory.api.web;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import at.aimon.memory.api.Bounds;
import at.aimon.memory.api.dto.Dtos;
import at.aimon.memory.api.dto.Requests;
import at.aimon.memory.core.NotFoundException;
import at.aimon.memory.store.WorkspaceSettingsService;
import at.aimon.memory.store.repo.PeerRepository;
import at.aimon.memory.store.repo.SessionPeerRepository;
import at.aimon.memory.store.repo.SessionRepository;
import at.aimon.memory.store.repo.WorkspaceRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Workspaces, peers, sessions and session membership. */
@RestController
@Tag(name = "hierarchy", description = "Workspaces, peers, sessions, and who was in a session when.")
public class HierarchyController {

    private final WorkspaceRepository workspaces;
    private final PeerRepository peers;
    private final SessionRepository sessions;
    private final SessionPeerRepository sessionPeers;
    private final WorkspaceSettingsService settings;

    public HierarchyController(WorkspaceRepository workspaces, PeerRepository peers, SessionRepository sessions,
            SessionPeerRepository sessionPeers, WorkspaceSettingsService settings) {
        this.workspaces = workspaces;
        this.peers = peers;
        this.sessions = sessions;
        this.sessionPeers = sessionPeers;
        this.settings = settings;
    }

    @Operation(summary = "List workspaces")
    @GetMapping("/v1/workspaces")
    public Dtos.PageResponse<Dtos.WorkspaceResponse> listWorkspaces(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return Dtos.PageResponse.of(workspaces.list(Bounds.page(page), Bounds.size(size)),
                HierarchyController::toWorkspace);
    }

    /** Create-or-get: idempotent, so a client can call it on every start without checking first. */
    @Operation(summary = "Create or get a workspace")
    @PostMapping("/v1/workspaces/{workspace}")
    public Dtos.WorkspaceResponse createWorkspace(@PathVariable String workspace,
            @RequestBody(required = false) Requests.CreateWorkspace body) {
        Requests.CreateWorkspace request = body == null ? new Requests.CreateWorkspace(Map.of(), Map.of()) : body;
        return toWorkspace(workspaces.getOrCreate(workspace, request.metadata() == null ? Map.of() : request.metadata(),
                request.configuration() == null ? Map.of() : request.configuration()));
    }

    @Operation(summary = "Get a workspace")
    @GetMapping("/v1/workspaces/{workspace}")
    public Dtos.WorkspaceResponse getWorkspace(@PathVariable String workspace) {
        return workspaces.find(workspace).map(HierarchyController::toWorkspace)
                .orElseThrow(() -> new NotFoundException("workspace", workspace));
    }

    /**
     * Replace a workspace's tuning configuration.
     *
     * <p>Validated before it is stored — in {@link WorkspaceRepository}, so every route that writes a
     * configuration column gets the check rather than the one that was noticed. Anything unrecognised
     * or out of range is a 422 rather than a 200 followed by a silent fallback to defaults on the next
     * read: the failure mode that makes a tuning session produce default rankings with nothing to
     * indicate why.
     */
    @Operation(summary = "Replace a workspace's tuning configuration")
    @PutMapping("/v1/workspaces/{workspace}/configuration")
    public Dtos.WorkspaceResponse updateWorkspaceConfiguration(@PathVariable String workspace,
            @Valid @RequestBody Requests.UpdateConfiguration body) {
        workspaces.updateConfiguration(workspace, body.configuration());
        // Evicted rather than left to expire: a tuning change that takes effect minutes later makes
        // the whole exercise of tuning against live traffic useless.
        settings.invalidate(workspace);
        return getWorkspace(workspace);
    }

    @Operation(summary = "Create or get a peer")
    @PostMapping("/v1/workspaces/{workspace}/peers/{peer}")
    public Dtos.PeerResponse createPeer(@PathVariable String workspace, @PathVariable String peer,
            @RequestBody(required = false) Requests.CreatePeer body) {
        Requests.CreatePeer request = body == null ? new Requests.CreatePeer(Map.of(), Map.of()) : body;
        var saved = peers.getOrCreate(workspace, peer, request.metadata() == null ? Map.of() : request.metadata(),
                request.configuration() == null ? Map.of() : request.configuration());
        return new Dtos.PeerResponse(saved.name(), saved.metadata(), saved.configuration(), saved.createdAt());
    }

    @Operation(summary = "List a workspace's peers")
    @GetMapping("/v1/workspaces/{workspace}/peers")
    public Dtos.PageResponse<Dtos.PeerResponse> listPeers(@PathVariable String workspace,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return Dtos.PageResponse.of(peers.list(workspace, Bounds.page(page), Bounds.size(size)),
                p -> new Dtos.PeerResponse(p.name(), p.metadata(), p.configuration(), p.createdAt()));
    }

    @Operation(summary = "Get a peer")
    @GetMapping("/v1/workspaces/{workspace}/peers/{peer}")
    public Dtos.PeerResponse getPeer(@PathVariable String workspace, @PathVariable String peer) {
        return peers.find(workspace, peer)
                .map(p -> new Dtos.PeerResponse(p.name(), p.metadata(), p.configuration(), p.createdAt()))
                .orElseThrow(() -> new NotFoundException("peer", peer));
    }

    @Operation(summary = "Replace a peer's configuration")
    @PutMapping("/v1/workspaces/{workspace}/peers/{peer}/configuration")
    public Dtos.PeerResponse updatePeerConfiguration(@PathVariable String workspace, @PathVariable String peer,
            @Valid @RequestBody Requests.UpdateConfiguration body) {
        peers.updateConfiguration(workspace, peer, body.configuration());
        return getPeer(workspace, peer);
    }

    @Operation(summary = "Create or get a session")
    @PostMapping("/v1/workspaces/{workspace}/sessions/{session}")
    public Dtos.SessionResponse createSession(@PathVariable String workspace, @PathVariable String session,
            @RequestBody(required = false) Requests.CreateSession body) {
        Requests.CreateSession request = body == null ? new Requests.CreateSession(Map.of(), Map.of()) : body;
        var saved = sessions.getOrCreate(workspace, session, request.metadata() == null ? Map.of() : request.metadata(),
                request.configuration() == null ? Map.of() : request.configuration());
        return new Dtos.SessionResponse(saved.name(), saved.isActive(), saved.metadata(), saved.createdAt());
    }

    @Operation(summary = "List a workspace's sessions")
    @GetMapping("/v1/workspaces/{workspace}/sessions")
    public Dtos.PageResponse<Dtos.SessionResponse> listSessions(@PathVariable String workspace,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return Dtos.PageResponse.of(sessions.list(workspace, Bounds.page(page), Bounds.size(size)),
                s -> new Dtos.SessionResponse(s.name(), s.isActive(), s.metadata(), s.createdAt()));
    }

    @Operation(summary = "Get a session")
    @GetMapping("/v1/workspaces/{workspace}/sessions/{session}")
    public Dtos.SessionResponse getSession(@PathVariable String workspace, @PathVariable String session) {
        return sessions.find(workspace, session)
                .map(s -> new Dtos.SessionResponse(s.name(), s.isActive(), s.metadata(), s.createdAt()))
                .orElseThrow(() -> new NotFoundException("session", session));
    }

    @Operation(summary = "List a session's membership")
    @GetMapping("/v1/workspaces/{workspace}/sessions/{session}/peers")
    public List<Dtos.SessionPeerResponse> listSessionPeers(@PathVariable String workspace,
            @PathVariable String session) {
        return sessionPeers.members(workspace, session).stream().map(p -> new Dtos.SessionPeerResponse(p.peerName(),
                p.observeMe(), p.observeOthers(), p.joinedAt(), p.leftAt())).toList();
    }

    @Operation(summary = "Add peers to a session")
    @PostMapping("/v1/workspaces/{workspace}/sessions/{session}/peers")
    public List<Dtos.SessionPeerResponse> addSessionPeers(@PathVariable String workspace, @PathVariable String session,
            @Valid @RequestBody Requests.AddSessionPeers body) {
        for (Requests.SessionPeerSpec spec : body.peers()) {
            peers.getOrCreate(workspace, spec.peer(), Map.of(), Map.of());
            sessionPeers.join(workspace, session, spec.peer(), spec.observeMe(), spec.observeOthers());
        }
        return listSessionPeers(workspace, session);
    }

    /** Replace the roster wholesale; anyone not listed has their membership window closed. */
    @Operation(summary = "Replace a session's roster")
    @PutMapping("/v1/workspaces/{workspace}/sessions/{session}/peers")
    public List<Dtos.SessionPeerResponse> replaceSessionPeers(@PathVariable String workspace,
            @PathVariable String session, @Valid @RequestBody Requests.AddSessionPeers body) {
        for (Requests.SessionPeerSpec spec : body.peers()) {
            peers.getOrCreate(workspace, spec.peer(), Map.of(), Map.of());
        }
        sessionPeers.replace(workspace, session, body.peers().stream()
                .map(spec -> new SessionPeerRepository.Membership(spec.peer(), spec.observeMe(), spec.observeOthers()))
                .toList());
        return listSessionPeers(workspace, session);
    }

    @Operation(summary = "Remove a peer from a session")
    @DeleteMapping("/v1/workspaces/{workspace}/sessions/{session}/peers/{peer}")
    public List<Dtos.SessionPeerResponse> removeSessionPeer(@PathVariable String workspace,
            @PathVariable String session, @PathVariable String peer) {
        sessionPeers.leave(workspace, session, peer);
        return listSessionPeers(workspace, session);
    }

    private static Dtos.WorkspaceResponse toWorkspace(at.aimon.memory.core.model.Workspace w) {
        return new Dtos.WorkspaceResponse(w.name(), w.metadata(), w.configuration(), w.createdAt());
    }
}
