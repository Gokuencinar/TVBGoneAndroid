# IR Universal Android — estado del port

Este directorio contiene la reimplementación Android de TVBGoneAudio / IR Universal.

## Objetivo actual

- Android 11 o superior (`minSdk 30`).
- Kotlin, sin dependencias de UI externas.
- Mantener los formatos de señal del proyecto iOS.
- Soportar dos rutas de transmisión:
  - emisor IR integrado mediante `ConsumerIrManager`;
  - adaptador IR estéreo mediante `AudioTrack`.
- Importar archivos Flipper `.ir`.
- Consultar Flipper-IRDB, Flipper IRDB oficial e IRDB Web.
- Aprender señales mediante un receptor IR conectado a una entrada de audio.
- Guardar equipos y señales localmente.

## Implementado en esta primera versión

- Base universal prioritaria portada desde iOS.
- Base TV-B-Gone original generada desde `WORLD_IR_CODES.h`:
  - 137 códigos Norteamérica/Asia;
  - 138 códigos Europa.
- Protocolos: NEC, NEC Extended, Samsung32, Sony SIRC 12/15/20, RC5, RC6,
  JVC, Kaseikyo, RCA y Pioneer.
- Backend IR integrado Android.
- Backend de audio diferencial L/R equivalente al de iOS.
- Barrido rápido / identificar.
- Importación Flipper RAW y parsed.
- Exportador Flipper RAW en el núcleo.
- Biblioteca online.
- Captura IR inicial por `AudioRecord`.
- Detección heurística básica de protocolo.
- Mis equipos con almacenamiento mediante SharedPreferences/JSON.
- UI OLED con secciones Control, Códigos, Online, Aprender, Equipos y Diagnóstico.
- GitHub Actions para generar `app-debug.apk`.

## Diferencias respecto a iOS 5.5

La lógica principal de emisión está portada, pero esta es todavía una primera versión Android.
La interfaz no replica todavía todas las animaciones, navegador A-Z/lista-ruleta, copia de seguridad
completa ni el refinamiento del IR Studio de iOS.

Para aire acondicionado y proyectores, la primera versión utiliza la biblioteca online/importación;
la base Flipper POWER/OFF offline todavía no se empaqueta dentro del APK.

## Compilar

Se usa Android Gradle Plugin 8.9.2, Gradle 8.11.1 y Java 17.
El APK de depuración se genera automáticamente por GitHub Actions en la rama `android-port`.

También puede compilarse desde Android Studio abriendo la carpeta `Android`.

## Hardware

### IR integrado

Android expone `ConsumerIrManager` en dispositivos que incorporan blaster IR. La app detecta
automáticamente este hardware en modo Automático.

### Adaptador de audio

Se mantiene el método de iOS: tono de audio a la mitad de la portadora IR, canal derecho invertido
respecto al izquierdo y reinicio de fase al comienzo de cada MARK.

Una ruta de 96 kHz es preferible. Algunas implementaciones Android pueden remuestrear la salida.

### Aprendizaje

El adaptador de LEDs de salida no puede aprender señales por sí solo. Se requiere un receptor IR
demodulado conectado a una entrada de audio compatible.
