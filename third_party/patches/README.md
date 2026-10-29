# Lokale rettelser til Calimero
Anvendes automatisk efter hentning af de pinnede commits (`tools/fetch-calimero.sh`, `tools/build.ps1`).
Calimero er GPLv2 + Classpath-undtagelse; rettelserne er offentliggjort her.

| Patch | Hvorfor |
|---|---|
| `calimero-server-flat-network.patch` | Calimeros KNXnet/IP-server opfører sig som én linje og dropper telegrammer til adresser uden for serverens egen linje (1.1.x). Med `-Dvknx.flatNetwork=true` (sat af vKNX) kan enheder have vilkårlig område/linje, så ETS kan downloade til f.eks. 2.3.4. Linjekoblerens filtertabel/hop count er ikke implementeret endnu. |
