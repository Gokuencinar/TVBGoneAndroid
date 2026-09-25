# Firma estable de TVBGoneAndroid

Las APK publicadas en Releases deben usar siempre la misma identidad de firma.
Android solo permite actualizar una aplicación instalada si el nuevo APK tiene:

1. el mismo `applicationId`;
2. un `versionCode` superior; y
3. **la misma firma criptográfica**.

Las builds antiguas usaban `assembleDebug`, por lo que cada runner efímero de
GitHub Actions podía generar una clave debug distinta. Eso provocaba
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` / "conflicto con un paquete existente".

## Configuración inicial (una sola vez)

Ejecuta en un equipo de confianza:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/setup_android_signing.ps1
```

El script:

- genera una keystore PKCS#12 de 4096 bits;
- la guarda **solo** en `scripts/.signing-private/`;
- sube a GitHub Actions Secrets:
  - `ANDROID_SIGNING_KEYSTORE_BASE64`
  - `ANDROID_SIGNING_STORE_PASSWORD`
  - `ANDROID_SIGNING_KEY_ALIAS`
  - `ANDROID_SIGNING_KEY_PASSWORD`

La keystore y sus contraseñas **no deben subirse al repositorio**.

Haz al menos una copia privada y segura de la keystore. Si se pierde la clave,
Android no aceptará actualizaciones futuras sobre las APK firmadas con ella.

## Pipeline

- Pull requests: `:app:assembleDebug` para validación, sin acceso a la clave.
- Push a `main`: restaura la keystore desde Secrets y ejecuta
  `:app:assembleRelease`.
- La Release publica `TVBGoneAndroid.apk`.
- Antes de publicar se ejecuta `apksigner verify --print-certs`.

Si falta cualquiera de los secretos, la publicación falla intencionadamente
en lugar de crear otra APK con una firma temporal.

## Migración desde builds antiguas

La primera APK con la nueva firma permanente no puede instalarse encima de una
build antigua firmada con una debug key diferente.

Para esa única migración:

1. exporta una copia JSON desde TVBGoneAndroid;
2. desinstala la build antigua;
3. instala la primera Release con firma estable;
4. restaura la copia JSON.

A partir de ahí, las siguientes Releases firmadas con esta misma keystore
podrán instalarse directamente encima sin borrar la app.
