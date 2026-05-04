package dev.saseq.primobot.ops;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ops/alerts")
public class OpsAlertController {
    private final OpsAlertService alertService;
    private final String alertToken;

    public OpsAlertController(OpsAlertService alertService,
                              @Value("${OPS_ALERT_TOKEN:}") String alertToken) {
        this.alertService = alertService;
        this.alertToken = alertToken == null ? "" : alertToken.trim();
    }

    @PostMapping
    public ResponseEntity<String> sendAlert(@RequestBody OpsAlertRequest request,
                                            @RequestHeader(name = "X-Ops-Alert-Token", required = false) String token) {
        if (!alertToken.isBlank() && !alertToken.equals(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("invalid token");
        }

        OpsAlertService.AlertResult result = alertService.sendAlert(request);
        if (!result.sent()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result.message());
        }

        return ResponseEntity.ok(result.message());
    }
}
