# Tryb: hub (lobby)

Lekkie lobby, na ktore trafia gracz po zalogowaniu - shardowane jak inne tryby
(hub-1, hub-2, ...). Routing po auth kieruje na najmniej obciazony shard trybu hub.

Bazowy swiat to plaski adventure. Wlasna mape lobby wrzuc do `templates/hub/world/`,
a pluginy lobby (selektor trybow, scoreboard) do `templates/hub/plugins/`.

Odpalenie:  new-shard.ps1 -Mode hub -Count 1
