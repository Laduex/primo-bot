package dev.saseq.primobot.meta;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhooks/meta")
public class MetaWebhookController {
    private static final Logger LOG = LoggerFactory.getLogger(MetaWebhookController.class);

    private final MetaWebhookRelayService relayService;

    public MetaWebhookController(MetaWebhookRelayService relayService) {
        this.relayService = relayService;
    }

    @GetMapping
    public ResponseEntity<String> verify(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String verifyToken,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if (relayService.isVerificationRequestValid(mode, verifyToken)) {
            return ResponseEntity.ok(challenge == null ? "" : challenge);
        }

        LOG.warn("Rejected Meta webhook verification request.");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("forbidden");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> receive(
            @RequestBody(required = false) String body,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature) {
        if (!relayService.isSignatureValid(body, signature)) {
            LOG.warn("Rejected Meta webhook request due to invalid signature.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("invalid signature");
        }

        int relayedCount = relayService.relayInboundChatPayload(body);
        if (relayedCount > 0) {
            LOG.info("Relayed {} Meta inbound chat event(s) to Discord.", relayedCount);
        }

        return ResponseEntity.ok("EVENT_RECEIVED");
    }
}
