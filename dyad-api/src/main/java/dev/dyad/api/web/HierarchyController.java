package dev.dyad.api.web;

import dev.dyad.api.Bounds;
import dev.dyad.api.dto.Dtos;
import dev.dyad.api.dto.Requests;
import dev.dyad.core.NotFoundException;
import dev.dyad.store.WorkspaceSettingsService;
import dev.dyad.store.repo.PeerRepository;
import dev.dyad.store.repo.SessionPeerRepository;
import dev.dyad.store.repo.SessionRepository;
import dev.dyad.store.repo.WorkspaceRepository;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Workspaces, peers, sessions and session membership. */
@RestController
public class HierarchyController {

    private final WorkspaceRepository workspaces;
    private final PeerRepository peers;
    private final SessionRepository sessions;
    private final SessionPeerRepository sessionPeers;
    private final WorkspaceSettingsService settings;

    public HierarchyController(
            WorkspaceRepository workspaces,
            PeerRepository peers,
            SessionRepository sessions,
            SessionPeerRepository sessionPeers,
            WorkspaceSettingsService settings) {
        this.workspaces = workspaces;
        this.peers = peers;
        this.sessions = sessions;
        this.sessionPeers = sessionPeers;
        this.settings = settings;
    }

    @GetMapping("/v1/workspaces")
    public Dtos.PageResponse<Dtos.WorkspaceResponse> listWorkspaces(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return Dtos.PageResponse.of(
                workspaces.list(Bounds.page(page), Bounds.size(size)), HierarchyController::toWorkspace);
    }

    /** Create-or-get: idempotent, so a client can call it on every start without checking first. */
    @PostMapping("/v1/workspaces/{workspace}")
    public Dtos.WorkspaceResponse createWorkspace(
            @PathVariable String workspace, @RequestBody(required = false) Requests.CreateWorkspace body) {
        Requests.CreateWorkspace request =
                body == null ? new Requests.CreateWorkspace(Map.of(), Map.of()) : body;
        return toWorkspace(
                workspaces.getOrCreate(
                        workspace,
                        request.metadata() == null ? Map.of() : request.metadata(),
                        request.configuration() == null ? Map.of() : request.configuration()));
    }

    @GetMapping("/v1/workspaces/{workspace}")
    public Dtos.WorkspaceResponse getWorkspace(@PathVariable String workspace) {
        return workspaces
                .find(workspace)
                .map(HierarchyController::toWorkspace)
                .orElseThrow(() -> new NotFoundException("workspace", workspace));
    }

    @PutMapping("/v1/workspaces/{workspace}/configuration")
    public Dtos.WorkspaceResponse updateWorkspaceConfiguration(
            @PathVariable String workspace, @Valid @RequestBody Requests.UpdateConfiguration body) {
        workspaces.updateConfiguration(workspace, body.configuration());
        // Evicted rather than left to expire: a tuning change that takes effect minutes later makes
        // the whole exercise of tuning against live traffic useless.
        settings.invalidate(workspace);
        return getWorkspace(workspace);
    }

    @PostMapping("/v1/workspaces/{workspace}/peers/{peer}")
    public Dtos.PeerResponse createPeer(
            @PathVariable String workspace,
            @PathVariable String peer,
            @RequestBody(required = false) Requests.CreatePeer body) {
        Requests.CreatePeer request = body == null ? new Requests.CreatePeer(Map.of(), Map.of()) : body;
        var saved =
                peers.getOrCreate(
                        workspace,
                        peer,
                        request.metadata() == null ? Map.of() : request.metadata(),
                        request.configuration() == null ? Map.of() : request.configuration());
        return new Dtos.PeerResponse(saved.name(), saved.metadata(), saved.configuration(), saved.createdAt());
    }

    @GetMapping("/v1/workspaces/{workspace}/peers")
    public Dtos.PageResponse<Dtos.PeerResponse> listPeers(
            @PathVariable String workspace,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return Dtos.PageResponse.of(
                peers.list(workspace, Bounds.page(page), Bounds.size(size)),
                p -> new Dtos.PeerResponse(p.name(), p.metadata(), p.configuration(), p.createdAt()));
    }

    @GetMapping("/v1/workspaces/{workspace}/peers/{peer}")
    public Dtos.PeerResponse getPeer(@PathVariable String workspace, @PathVariable String peer) {
        return peers
                .find(workspace, peer)
                .map(p -> new Dtos.PeerResponse(p.name(), p.metadata(), p.configuration(), p.createdAt()))
                .orElseThrow(() -> new NotFoundException("peer", peer));
    }

    @PutMapping("/v1/workspaces/{workspace}/peers/{peer}/configuration")
    public Dtos.PeerResponse updatePeerConfiguration(
            @PathVariable String workspace,
            @PathVariable String peer,
            @Valid @RequestBody Requests.UpdateConfiguration body) {
        peers.updateConfiguration(workspace, peer, body.configuration());
        return getPeer(workspace, peer);
    }

    @PostMapping("/v1/workspaces/{workspace}/sessions/{session}")
    public Dtos.SessionResponse createSession(
            @PathVariable String workspace,
            @PathVariable String session,
            @RequestBody(required = false) Requests.CreateSession body) {
        Requests.CreateSession request =
                body == null ? new Requests.CreateSession(Map.of(), Map.of()) : body;
        var saved =
                sessions.getOrCreate(
                        workspace,
                        session,
                        request.metadata() == null ? Map.of() : request.metadata(),
                        request.configuration() == null ? Map.of() : request.configuration());
        return new Dtos.SessionResponse(saved.name(), saved.isActive(), saved.metadata(), saved.createdAt());
    }

    @GetMapping("/v1/workspaces/{workspace}/sessions")
    public Dtos.PageResponse<Dtos.SessionResponse> listSessions(
            @PathVariable String workspace,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return Dtos.PageResponse.of(
                sessions.list(workspace, Bounds.page(page), Bounds.size(size)),
                s -> new Dtos.SessionResponse(s.name(), s.isActive(), s.metadata(), s.createdAt()));
    }

    @GetMapping("/v1/workspaces/{workspace}/sessions/{session}")
    public Dtos.SessionResponse getSession(@PathVariable String workspace, @PathVariable String session) {
        return sessions
                .find(workspace, session)
                .map(s -> new Dtos.SessionResponse(s.name(), s.isActive(), s.metadata(), s.createdAt()))
                .orElseThrow(() -> new NotFoundException("session", session));
    }

    @GetMapping("/v1/workspaces/{workspace}/sessions/{session}/peers")
    public List<Dtos.SessionPeerResponse> listSessionPeers(
            @PathVariable String workspace, @PathVariable String session) {
        return sessionPeers.members(workspace, session).stream()
                .map(p -> new Dtos.SessionPeerResponse(
                        p.peerName(), p.observeMe(), p.observeOthers(), p.joinedAt(), p.leftAt()))
                .toList();
    }

    @PostMapping("/v1/workspaces/{workspace}/sessions/{session}/peers")
    public List<Dtos.SessionPeerResponse> addSessionPeers(
            @PathVariable String workspace,
            @PathVariable String session,
            @Valid @RequestBody Requests.AddSessionPeers body) {
        for (Requests.SessionPeerSpec spec : body.peers()) {
            peers.getOrCreate(workspace, spec.peer(), Map.of(), Map.of());
            sessionPeers.join(workspace, session, spec.peer(), spec.observeMe(), spec.observeOthers());
        }
        return listSessionPeers(workspace, session);
    }

    /** Replace the roster wholesale; anyone not listed has their membership window closed. */
    @PutMapping("/v1/workspaces/{workspace}/sessions/{session}/peers")
    public List<Dtos.SessionPeerResponse> replaceSessionPeers(
            @PathVariable String workspace,
            @PathVariable String session,
            @Valid @RequestBody Requests.AddSessionPeers body) {
        for (Requests.SessionPeerSpec spec : body.peers()) {
            peers.getOrCreate(workspace, spec.peer(), Map.of(), Map.of());
        }
        sessionPeers.replace(
                workspace,
                session,
                body.peers().stream()
                        .map(spec -> new SessionPeerRepository.Membership(
                                spec.peer(), spec.observeMe(), spec.observeOthers()))
                        .toList());
        return listSessionPeers(workspace, session);
    }

    @DeleteMapping("/v1/workspaces/{workspace}/sessions/{session}/peers/{peer}")
    public List<Dtos.SessionPeerResponse> removeSessionPeer(
            @PathVariable String workspace, @PathVariable String session, @PathVariable String peer) {
        sessionPeers.leave(workspace, session, peer);
        return listSessionPeers(workspace, session);
    }

    private static Dtos.WorkspaceResponse toWorkspace(dev.dyad.core.model.Workspace w) {
        return new Dtos.WorkspaceResponse(w.name(), w.metadata(), w.configuration(), w.createdAt());
    }
}
