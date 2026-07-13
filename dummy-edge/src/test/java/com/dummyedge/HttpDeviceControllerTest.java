package com.dummyedge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The catch-all accepts a cloud→edge delivery on ANY path — recording it and echoing it back —
 * while the exact {@code /commands} mapping still wins for that path (Spring matches exact routes
 * before patterns).
 */
@WebMvcTest(HttpDeviceController.class)
class HttpDeviceControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ConfirmPusher confirmPusher;
    @MockitoBean
    private ReceivedStore receivedStore;
    @MockitoBean
    private EdgeProperties properties;

    @Test
    void catchAllAcceptsAnyPathRecordsItAndEchoesBack() throws Exception {
        mvc.perform(post("/waves")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pickTaskId\":\"P1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.channel").value("/waves"));

        verify(receivedStore).add(eq("HTTP"), eq("/waves"), eq("{\"pickTaskId\":\"P1\"}"));
        verify(confirmPusher).pushHttpEcho(eq("/waves"), eq("{\"pickTaskId\":\"P1\"}"));  // reply on the request channel
        verify(confirmPusher, never()).pushHttpCommandResult(anyString());
    }

    @Test
    void commandsPathStillRoutesToTheStructuredHandler() throws Exception {
        mvc.perform(post("/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandId\":\"C1\",\"action\":\"scan\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"));

        verify(confirmPusher).pushHttpCommandResult(anyString());
        verify(confirmPusher, never()).pushHttpEcho(anyString(), anyString());
    }
}
