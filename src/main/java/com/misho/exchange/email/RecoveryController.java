package com.misho.exchange.email;

import java.security.Principal;
import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class RecoveryController {
    public record EmailRequest(@NotBlank @Email @Size(max=254) String email) { public EmailRequest { email=email==null?null:email.strip().toLowerCase(java.util.Locale.ROOT); } }
    public record TokenRequest(@NotBlank @Size(max=128) String token) { @Override public String toString(){return "TokenRequest[redacted]";} }
    public record ResetRequest(@NotBlank @Size(max=128) String token,@NotBlank @Size(min=15,max=128) String password) { @Override public String toString(){return "ResetRequest[redacted]";} }
    public record PreferenceRequest(boolean alertEmails) { }
    private final AccountRecovery recovery;
    public RecoveryController(AccountRecovery recovery){this.recovery=recovery;}
    @PostMapping("/forgot-password") @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,String> forgot(@Valid @RequestBody EmailRequest request) {
        recovery.forgot(request.email()); return Map.of("message","If an account matches that address, a password-reset email will be sent. Check your inbox and spam folder.");
    }
    @PostMapping("/reset-password") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@Valid @RequestBody ResetRequest request){recovery.reset(request.token(),request.password());}
    @PostMapping("/verify-email") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@Valid @RequestBody TokenRequest request){recovery.confirmEmail(request.token());}
    @PostMapping("/email-verification") @ResponseStatus(HttpStatus.ACCEPTED)
    public void verify(Principal user){recovery.verify(user.getName());}
    @GetMapping("/email-preferences") public AccountRecovery.Preferences preferences(Principal user){return recovery.preferences(user.getName());}
    @PostMapping("/email-preferences") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void preferences(Principal user,@RequestBody PreferenceRequest request){recovery.preferences(user.getName(),request.alertEmails());}
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid(IllegalArgumentException e){return Map.of("error",e.getMessage());}
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> unavailable(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("error",e.getReason()));}
}
