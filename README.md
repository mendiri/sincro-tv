# Sincro TV

App para Fire TV / Android TV: guía de partidos (Live Guide o futbolenlatv.es), tu lista IPTV con VPN WireGuard integrada
y la radio retrasada para que vaya sincronizada con la imagen.

## Instalar

En el Fire TV, con la app **Downloader**, abre:

    https://github.com/mendiri/sincro-tv/releases/latest/download/SincroTV.apk

Después la propia app avisa cuando hay versión nueva y se actualiza desde la tele.

## Publicar una versión

Sube `versionCode` y `versionName` en `app/build.gradle.kts` y haz push a `main`.
GitHub Actions compila, firma y publica el APK (los datos de la firma están en los secretos del repositorio).
