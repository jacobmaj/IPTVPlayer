# Majano IPTV — starter Android

Starter de aplicación Android nativa con Compose y Android Media3/ExoPlayer. Incluye una interfaz inicial adaptable a móvil/TV, navegación de secciones, configuración de proveedor y flujo de GitHub Actions para compilar un APK de depuración.

## Estado del proyecto (v0.2.0)

Funciona: login con link + usuario + contraseña (Xtream Codes, con alternativa M3U), carga de TV en vivo, películas y series (temporadas/episodios), búsqueda, categorías, favoritos persistentes, sesión guardada cifrada, pantalla completa y cambio de canal con CH+/CH-.
Pendiente: guía EPG, selector de audio/subtítulos, caché del catálogo en disco.

## Requisitos

- Cuenta de GitHub.
- PC con navegador; no necesitas instalar Android Studio para obtener el APK de prueba si GitHub Actions compila correctamente.
- Para desarrollo local: Android Studio, JDK 17 y Android SDK.
- Acceso legítimo a un proveedor IPTV y sus datos de conexión.

## Subir a GitHub

1. Descarga y descomprime este ZIP.
2. En GitHub, crea un repositorio nuevo llamado `MajanoIPTV`. Déjalo vacío, sin README ni licencia generados por GitHub.
3. Dentro del repositorio, pulsa **Add file → Upload files**.
4. Sube el contenido de la carpeta `MajanoIPTV` (incluida `.github/workflows/build-apk.yml`, que puede estar oculta en algunos exploradores de archivos).
5. Pulsa **Commit changes**.
6. Abre la pestaña **Actions**. El flujo `Build Android APK` debería iniciar al subir el código. También puedes abrirlo y pulsar **Run workflow**.
7. Si termina en verde, abre la ejecución y descarga el artefacto `majano-iptv-debug-apk`. Dentro está `app-debug.apk`.
8. Copia el APK al dispositivo e instálalo. Puede ser necesario permitir la instalación desde esa fuente.

## Nota sobre el control remoto

Las flechas, OK y Atrás se pueden gestionar dentro de la aplicación si Android entrega esos eventos. El volumen lo gestiona normalmente el sistema. Las teclas físicas de canal +/- no están estandarizadas y pueden no llegar a aplicaciones de terceros. Deben probarse en cada TV Box/televisor.

## 4K

Media3/ExoPlayer puede reproducir formatos compatibles con el dispositivo y el stream, pero la aplicación no puede garantizar 4K en hardware sin decodificación compatible, ni convertir una fuente de menor resolución a 4K. El rendimiento depende del dispositivo, códec, ancho de banda y proveedor.

## Seguridad

No subas credenciales reales a GitHub ni las escribas en código. Las credenciales se guardan cifradas con Android Keystore (EncryptedSharedPreferences). Se permite tráfico http porque casi todos los proveedores IPTV no usan https.


## Icono de la aplicación
Se agregó el icono Majano IPTV para el launcher de Android, con recursos mipmap y soporte de icono adaptativo en Android 8 o posterior.
