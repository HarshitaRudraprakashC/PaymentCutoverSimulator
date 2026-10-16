package org.payment;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract: what a client actually sees. The service tests prove the logic;
 * these prove the status codes and error codes a merchant's code would depend on.
 */
class ApiContractTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;

    @Test
    void authorizeWithoutIdempotencyKey_is400() throws Exception {
        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(body("m-1", 1_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
    }

    @Test
    void negativeAmount_is400() throws Exception {
        mvc.perform(authorize(key(), body("m-1", -5)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** S5 over HTTP: same key, different request. */
    @Test
    void sameKeyDifferentAmount_is422() throws Exception {
        String key = key();
        String merchant = "m-" + UUID.randomUUID();
        mvc.perform(authorize(key, body(merchant, 1_000))).andExpect(status().isCreated());
        mvc.perform(authorize(key, body(merchant, 2_000)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void sameKeySameRequest_returnsTheIdenticalAnswer() throws Exception {
        String key = key();
        String request = body("m-" + UUID.randomUUID(), 3_000);
        String first = mvc.perform(authorize(key, request)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(authorize(key, request)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);          // byte for byte: same id, same timestamps
    }

    @Test
    void unknownPayment_is404() throws Exception {
        mvc.perform(get("/payments/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void capturingAnAlreadyCapturedPayment_withANewKey_is409() throws Exception {
        String created = mvc.perform(authorize(key(), body("m-" + UUID.randomUUID(), 4_000)))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        mvc.perform(post("/payments/" + id + "/capture").header("Idempotency-Key", key()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
        mvc.perform(post("/payments/" + id + "/capture").header("Idempotency-Key", key()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    // ---------------------------------------------------------------- helpers

    private static org.springframework.test.web.servlet.RequestBuilder authorize(String key, String json) {
        return post("/payments").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private static String body(String merchant, long amount) {
        return "{\"merchantId\":\"" + merchant + "\",\"amount\":" + amount + ",\"currency\":\"EUR\"}";
    }

    private static String key() {
        return "k-" + UUID.randomUUID();
    }
}