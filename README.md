# MusicPaper

App Android **open source** (MIT) que pone la carátula de la canción que suena como fondo de pantalla (bloqueo y/o inicio). Sin anuncios, sin cuentas, sin datos guardados.

## Funciones (v0.1)
- Detecta Spotify y cualquier reproductor con sesión de medios
- Elegir qué apps de música usar
- Efectos: **Blur** (con intensidad) y **Cover**, más **Oscurecer**
- Aplicar en bloqueo, inicio o ambas
- Restaura tu fondo X segundos después de pausar

## Compilar con GitHub Actions (sin Android Studio)
1. Sube todo este proyecto a un repo de GitHub (rama `main`).
2. Ve a la pestaña **Actions** → se ejecuta **Build APK** solo (o pulsa *Run workflow*).
3. Cuando termine, abre la ejecución y descarga el artefacto **MusicPaper-debug-apk**.
4. Para publicar un release: crea un tag `v0.1.0` y el APK se adjunta solo.

## Instalar y configurar
1. Instala el APK y ábrelo.
2. **Acceso a notificaciones**: actívalo. En Android 13+ si sale gris: Info de la app → ⋮ → *Permitir ajustes restringidos*.
3. **Sin ahorro de batería** (importante en Tecno/Infinix/Xiaomi, si no el sistema mata el servicio).
4. Elige una imagen de restauración y reproduce música.

## Cómo funciona
`NotificationListenerService` → `MediaSessionManager` → carátula de `MediaMetadata` → efectos en Bitmap → `WallpaperManager.setBitmap(...)`.

## Roadmap
- Efectos CD, Square, escala de carátula, altura de transición
- Efectos de baile (Edge Glow, Music Wave)
- Vista previa en la app, traducciones

## Licencia
MIT, ver `LICENSE`.
