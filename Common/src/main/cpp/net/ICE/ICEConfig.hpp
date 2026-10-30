#pragma once

#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

struct ICEConfigSnapshot {
    std::string rendezvousHost;
    uint16_t rendezvousPort{6789};
    bool useTurnForStun{false};
    bool verifyRendezvousCertificate{true};
    bool useLocalDiscovery{true};
    bool preferIPv4{false};
};

ICEConfigSnapshot currentICEConfig();
void updateICEConfig(std::string rendezvousHost, uint16_t rendezvousPort,
                     bool useTurnForStun, bool verifyRendezvousCertificate,
                     bool useLocalDiscovery, bool preferIPv4 = false);

struct RendezvousEndpoint {
    std::string host;
    uint16_t port;
    bool verifyCertificate;
};

// Primary first; see RendezvousFailover.hpp.
std::vector<RendezvousEndpoint> resolveRendezvousEndpoints(std::string_view label);
