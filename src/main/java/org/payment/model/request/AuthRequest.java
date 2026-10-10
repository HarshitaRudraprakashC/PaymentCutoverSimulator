package org.payment.model.request;

import lombok.NonNull;
import jakarta.validation.constraints.Pattern;

public record AuthRequest(@NonNull String merchantId,
                          @NonNull Integer amount,
                          @NonNull  @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO code like EUR") String currency) {
}
