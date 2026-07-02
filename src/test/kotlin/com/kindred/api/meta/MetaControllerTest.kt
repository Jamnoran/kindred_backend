package com.kindred.api.meta

import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class MetaControllerTest {

    private val mockMvc = MockMvcBuilders.standaloneSetup(MetaController("test")).build()

    @Test
    fun `meta endpoint reports instance info`() {
        mockMvc.perform(get("/api/v1/meta"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("kindred-api"))
            .andExpect(jsonPath("$.version").value("test"))
            .andExpect(jsonPath("$.openApiSpec").value("/v3/api-docs"))
    }
}
