from pathlib import Path
p = Path("app/src/main/java/com/raineywrapper/player/Extras.kt")
t = p.read_text()
viejo = 'Variable("ppsspp_change_pro_ad_hoc_server_address", "socom.cc"))),\n'
nuevo = viejo + '''        ExtraLevel("Anfitrion LAN", listOf(
            Variable("ppsspp_enable_wlan", "enabled"),
            Variable("ppsspp_enable_builtin_pro_ad_hoc_server", "enabled"),
            Variable("ppsspp_change_pro_ad_hoc_server_address", "localhost"))),
        ExtraLevel("Servidor + UPnP", listOf(
            Variable("ppsspp_enable_wlan", "enabled"),
            Variable("ppsspp_enable_upnp", "enabled"),
            Variable("ppsspp_change_pro_ad_hoc_server_address", "socom.cc"))),
'''
if t.count(viejo) == 1:
    Path("Extras.kt.bak").write_text(t)
    p.write_text(t.replace(viejo, nuevo))
    print("FIX MP OK")
else:
    print("NO ENCONTRE (%d coincidencias), no toque nada" % t.count(viejo))
