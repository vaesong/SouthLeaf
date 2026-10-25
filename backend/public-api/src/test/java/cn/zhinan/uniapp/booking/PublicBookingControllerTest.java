package cn.zhinan.uniapp.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhinan.uniapp.booking.application.PublicBookingService;
import cn.zhinan.uniapp.booking.config.PublicBookingProperties;
import cn.zhinan.uniapp.booking.controller.PublicBookingController;
import cn.zhinan.uniapp.booking.identity.TestSinkSubjectExchange;
import cn.zhinan.uniapp.booking.port.PublicBookingPorts.SlotRow;
import cn.zhinan.uniapp.booking.session.PublicSessionTokens;
import cn.zhinan.uniapp.common.GlobalExceptionHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class PublicBookingControllerTest {
  private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String SLOT = "11111111-2222-4333-8444-555555555555";
  private static final String REF = "pkgv-synthetic-1";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC);
  private static final ObjectMapper JSON = new ObjectMapper();

  private MockMvc mockMvc;
  private FakeRepository repository;

  @BeforeEach
  void setUp() {
    Map<String, String> env = new HashMap<>();
    env.put("SOUTHLEAF_PUBLIC_BOOKING_MODE", "enabled");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_DB_URL", "jdbc:mysql://127.0.0.1:3306/southleaf_admin");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_DB_USERNAME", "southleaf_public_booking_writer");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_DB_PASSWORD", "fixture-password");
    env.put(
        "SOUTHLEAF_PUBLIC_BOOKING_DB_EXPECTED_USER", "southleaf_public_booking_writer@localhost");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_DATA_MODE", "SYNTHETIC");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_IDENTITY_SINK", "TEST");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_APP_SCOPE", "southleaf-mp");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_SESSION_SECRET", SECRET);
    env.put("SOUTHLEAF_PUBLIC_BOOKING_SESSION_MINUTES", "120");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_REQUEST_HOURS", "48");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_BUSINESS_ZONE", "Asia/Tokyo");
    env.put("SOUTHLEAF_PUBLIC_BOOKING_PACKAGE_REFS", REF + "=虚构演示套餐");
    PublicBookingProperties properties = PublicBookingProperties.load(env, "southleaf_public");
    repository = new FakeRepository();
    repository.slots.put(
        SLOT,
        new SlotRow(
            SLOT,
            Instant.parse("2026-10-26T01:00:00Z"),
            Instant.parse("2026-10-26T02:30:00Z"),
            "Asia/Tokyo",
            "+09:00",
            "OPEN"));
    PublicBookingService service =
        new PublicBookingService(
            properties,
            repository,
            new TestSinkSubjectExchange(),
            new PublicSessionTokens(SECRET, properties.sessionLifetime()),
            CLOCK);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new PublicBookingController(service, CLOCK))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private String token() throws Exception {
    var result =
        mockMvc
            .perform(
                post("/api/v1/public/booking/session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"loginCode\":\"test-alice-0001\"}"))
            .andReturn();
    String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(result.getResponse().getStatus()).as("session status body=" + body).isEqualTo(200);
    assertThat(JSON.readTree(body).path("meta").path("dataMode").asText()).isEqualTo("demo");
    return JSON.readTree(body).path("data").path("token").asText();
  }

  @Test
  void sessionReturnsASignedTokenAndTheSharedEnvelope() throws Exception {
    String token = token();
    assertThat(token).contains(".");
    assertThat(token).doesNotContain("alice");
  }

  @Test
  void requestWithoutABearerIsUnauthorized() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/public/booking/requests")
                .header("Idempotency-Key", "mp-order-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slotId\":\"" + SLOT + "\",\"packageVersionRef\":\"" + REF + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("BOOKING_SESSION_REQUIRED"));
  }

  @Test
  void unexpectedFieldIsRejectedRatherThanIgnored() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/public/booking/requests")
                .header("Authorization", "Bearer " + token())
                .header("Idempotency-Key", "mp-order-0002")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"slotId\":\""
                        + SLOT
                        + "\",\"packageVersionRef\":\""
                        + REF
                        + "\",\"amount\":1}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BOOKING_REQUEST_INVALID"));
  }

  @Test
  void acceptedRequestIsCreatedOnceAndListedForItsOwner() throws Exception {
    String token = token();
    mockMvc
        .perform(
            post("/api/v1/public/booking/requests")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "mp-order-0003")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slotId\":\"" + SLOT + "\",\"packageVersionRef\":\"" + REF + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.status").value("PENDING_CONFIRMATION"))
        .andExpect(jsonPath("$.data.order").doesNotExist())
        .andExpect(jsonPath("$.data.requestedSlot.localStart").value("10:00"));
    mockMvc
        .perform(get("/api/v1/public/booking/requests").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items.length()").value(1));
  }

  @Test
  void slotSearchRequiresAZoneDatedWindow() throws Exception {
    mockMvc
        .perform(get("/api/v1/public/booking/slots").param("from", "2026-10-26"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BOOKING_REQUEST_INVALID"));
  }
}
