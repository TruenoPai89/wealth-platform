package com.wealth.mod5_telephony;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Herramienta fund-dfi del agente de voz: busqueda por ISIN o nombre hablado, y nunca inventa datos. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "wealth.telephony.webhook-secret=dfi-secret")
class FundDfiTest {

    private static final String URL = "/api/v1/telephony/fund-dfi";
    private static final String HEADER = "X-Telephony-Secret";

    @Autowired
    private MockMvc mvc;

    @Test
    void findsFundByIsin() throws Exception {
        mvc.perform(get(URL).param("isin_or_name", "IE00B4L5Y983").header(HEADER, "dfi-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("iShares Core MSCI World"))
                .andExpect(jsonPath("$.terPct").value("0.20%"))
                .andExpect(jsonPath("$.srri").value("6/7"))
                .andExpect(jsonPath("$.notice").value(containsString("ilustrativos")));
    }

    @Test
    void findsFundBySpokenNameWithoutAccentsOrCase() throws Exception {
        mvc.perform(get(URL).param("isin_or_name", "el Msci  WORLD").header(HEADER, "dfi-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isin").value("IE00B4L5Y983"));
        mvc.perform(get(URL).param("isin_or_name", "oro").header(HEADER, "dfi-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Invesco Physical Gold"));
        mvc.perform(get(URL).param("isin_or_name", "Renta Fija").header(HEADER, "dfi-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.srri").value("3/7"));
    }

    @Test
    void unknownFundIs404AndListsAvailableOnesInsteadOfInventingData() throws Exception {
        mvc.perform(get(URL).param("isin_or_name", "Fondo Tech Innovation").header(HEADER, "dfi-secret"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString("Fondos disponibles")))
                .andExpect(jsonPath("$.error").value(containsString("Vanguard S&P 500")));
    }

    @Test
    void ambiguousNameIs409WithCandidates() throws Exception {
        mvc.perform(get(URL).param("isin_or_name", "ishares").header(HEADER, "dfi-secret"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("iShares Core MSCI World")))
                .andExpect(jsonPath("$.error").value(containsString("iShares Core Euro Govt Bond")));
    }

    @Test
    void requiresSecretHeader() throws Exception {
        mvc.perform(get(URL).param("isin_or_name", "oro")).andExpect(status().isUnauthorized());
        mvc.perform(get(URL).param("isin_or_name", "oro").header(HEADER, "nope")).andExpect(status().isUnauthorized());
    }

    @Test
    void blankOrMissingQueryIs400() throws Exception {
        mvc.perform(get(URL).param("isin_or_name", "  ").header(HEADER, "dfi-secret")).andExpect(status().isBadRequest());
        mvc.perform(get(URL).header(HEADER, "dfi-secret")).andExpect(status().isBadRequest());
    }

    @Test
    void normalizeStripsAccentsCaseAndPunctuation() {
        assertThat(FundDfiService.normalize("  Renta-Fíja!! ")).isEqualTo("renta fija");
        assertThat(FundDfiService.normalize("S&P 500")).isEqualTo("s&p 500");
    }
}
