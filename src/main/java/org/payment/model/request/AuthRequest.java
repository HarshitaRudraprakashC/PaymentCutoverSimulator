package org.payment.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record AuthRequest(@NotBlank String merchantId,
                          @Positive long amount,
                          @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO code like EUR") String currency) {
}
