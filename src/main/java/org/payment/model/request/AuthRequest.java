package org.payment.model.request;

import jakarta.validation.constraints.Pattern;

public record AuthRequest(String merchantId,
                          long amount,
                          @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO code like EUR") String currency) {
}
