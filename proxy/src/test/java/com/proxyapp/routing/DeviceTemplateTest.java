package com.proxyapp.routing;

import com.proxyapp.profile.DeviceFleetProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceTemplateTest {

    @Test
    void materializeFillsChannelsFromSiteValues() {
        DeviceTemplate template = new DeviceFleetProfile().deviceTemplates().get(0);
        EdgeConfig device = template.materialize(new DeviceTemplate.SiteValues(
                "gateway-7", "http://10.1.2.3:8082", "10.1.2.3", 6000, 2222, "u", "p"));

        assertThat(device.deviceId()).isEqualTo("gateway-7");
        assertThat(device.bindings()).hasSize(6);
        assertThat(binding(device, "DEVICE_COMMAND").channel()).isEqualTo(Channel.path("/commands"));
        assertThat(binding(device, "CONFIG_UPDATE").channel()).isEqualTo(Channel.port(6000));
        assertThat(binding(device, "CONFIG_ACK").channel()).isEqualTo(Channel.port(6001));
        assertThat(binding(device, "REPORT_REQUEST").channel()).isEqualTo(Channel.folder("report-requests"));
    }

    @Test
    void materializedTemplateValidatesCleanlyAgainstItsProfile() {
        DeviceFleetProfile profile = new DeviceFleetProfile();
        EdgeConfig device = profile.deviceTemplates().get(0).materialize(new DeviceTemplate.SiteValues(
                "gateway-7", "http://10.1.2.3:8082", "10.1.2.3", 6000, 2222, "u", "p"));
        List<Integer> pool = IntStream.rangeClosed(6000, 6010).boxed().toList();

        assertThat(ConfigValidator.validate(profile.catalog(), pool, List.of(device))).isEmpty();
    }

    private static RouteBinding binding(EdgeConfig device, String type) {
        return device.bindings().stream()
                .filter(b -> b.messageType().value().equals(type))
                .findFirst().orElseThrow();
    }
}
