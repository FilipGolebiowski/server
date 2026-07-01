# Tryb: oceanblock

Skyblock, w którym wyspa stoi na oceanie zamiast wisieć w powietrzu.

Szablon daje **bazowy świat** (płaski ocean) przez `server.properties.extra`. Sama rozgrywka
(generowanie wysp, wyzwania, `/is`) wymaga pluginu skyblock — wrzuć go do `plugins/`
(polecany: BentoBox + addon OceanBlock; oba pod Paper 1.21).

## Odpalenie

    powershell -ExecutionPolicy Bypass -File ..\..\new-shard.ps1 -Mode oceanblock -Count 2

Powstaną `servers\oceanblock-1/2` z tym światem + Twoim pluginem + `core-paper.jar`.
W grze: `/play oceanblock`.
