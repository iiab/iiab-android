# :world_map: Internet-in-a-Box en Android

**[Internet-in-a-Box (IIAB)](https://internet-in-a-box.org) en Android** permitirá que millones de personas en todo el mundo construyan sus propias bibliotecas familiares, ¡dentro de sus propios teléfonos!

A partir de Marzo de 2026, estas Apps de IIAB están soportadas:

* **Calibre-Web** (eBooks & videos)
* **Kiwix** (Wikipedias, etc)
* **Kolibri** (lecciones y cuestionarios)
* **Maps** (fotos satelitales, relieve, edificios)
* **Matomo** (métricas)

El puerto predeterminado del servidor web es **8085**, por ejemplo:

```
http://localhost:8085/maps
```

## ¿Cuáles son los componentes actuales de "IIAB en Android"?

* **[termux-setup](https://github.com/iiab/iiab-android/tree/main/termux-setup) (iiab-termux)** — prepara un entorno tipo Debian en Android (se llama [PRoot](https://wiki.termux.com/wiki/PRoot))
* **Wrapper para instalar IIAB (iiab-android)** — configura [`local_vars_android.yml`](https://github.com/iiab/iiab/blob/master/vars/local_vars_android.yml) y luego lanza el instalador de IIAB
* **Capa principal de portabilidad de IIAB** — modificaciones a través de IIAB y sus roles existentes, basado en el [PR #4122](https://github.com/iiab/iiab/pull/4122)
* **proot-distro service manager (PDSM)** — como systemd, pero para `proot_services`

## Documentación relacionada

* **Bootstrap de Android (en este repo):** [`termux-setup/README.md`](https://github.com/iiab/iiab-android/blob/main/termux-setup/README.md)
* **Rol proot_services (en el repo principal de IIAB):** [`roles/proot_services/README.md`](https://github.com/iiab/iiab/blob/master/roles/proot_services/README.md)

---

## :clipboard: Guía de instalación

Antes de instalar, necesitas configurar tu dispositivo Android. Estos pasos iniciales se aplican a todos los usuarios:

### Parte 1: Requisitos previos y configuración del dispositivo

1. **Instalar F-Droid y Termux**
    * Descarga e instala **F-Droid** ([https://f-droid.org/F-Droid.apk](https://f-droid.org/F-Droid.apk)). Tendrás que permitir la instalación desde fuentes desconocidas desde tu navegador web predeterminado.
    * Abre F-Droid y espera un momento a que los repositorios se actualicen automáticamente en segundo plano.
    * Instala **Termux** y **Termux:API**. La mayoría de los navegadores reconocerán estos enlaces directos después de instalar F-Droid:
      * [**Termux**](https://f-droid.org/packages/com.termux)
      * [**Termux:API**](https://f-droid.org/packages/com.termux.api)
      * *(Alternativamente, puedes simplemente buscarlos directamente dentro de la app F-Droid).*

    > **Nota:** Puede que veas la etiqueta *"Esta aplicación se creó para una versión anterior de Android..."*. Ignora esto; solo afecta a las actualizaciones automáticas. Las actualizaciones manuales seguirán funcionando. [Puedes aprender más sobre el tema aquí.](https://github.com/termux/termux-packages/wiki/Termux-and-Android-10/3e8102ecd05c4954d67971ff2b508f32900265f7)

2. **Configurar los ajustes de batería (Importante)**
    Para ejecutar la instalación o mantener vivos los servicios de IIAB en segundo plano, debes permitir que Termux se ejecute sin restricciones de batería.
    * Ve a los **Ajustes** de tu Android **-> Aplicaciones -> Termux -> Batería**.
    * Establécelo en **No restringido**, **No optimizar** o **Permitir actividad en segundo plano** (la etiqueta exacta varía según el fabricante). ¡Si dejas esto restringido, Android puede matar el proceso cuando tu pantalla se apague!

    > **Nota:** Debido a que esta política es importante para una configuración exitosa, nuestro script de instalación te pedirá que lo verifiques más adelante. ¡Gracias por prestar atención al manual! 😉

3. **Habilitar Opciones de desarrollador y Límites de procesos**
    * Ve a **Ajustes > Acerca del teléfono** (o Acerca de la tablet) y toca **Número de compilación** siete veces rápidamente para habilitar las Opciones de desarrollador.
    * **Para Android 14 y posteriores:** Regresa a **Ajustes -> Sistema -> Opciones de desarrollador** y activa `Inhabilitar restricciones de procesos secundarios`.
    * **Para Android 8 al 11:** No se aplican restricciones especiales de procesos. ¡Estás listo para continuar!
    * **Para Android 12 y 13:** *Por favor, consulta la sección especial al final de esta guía sobre el "Phantom Process Killer" (PPK) antes de continuar, ya que podría interrumpir tu instalación.*

---

### Parte 2: Elige tu ruta de instalación

Hay dos formas principales de instalar IIAB en Android. Si no estás seguro, recomendamos el método **Precompilado**.

**Precompilado - Rápido y sencillo**  
Esta es la ruta recomendada para la mayoría de los usuarios. En lugar de compilar el software en tu teléfono, descarga un sistema IIAB preconfigurado y listo para usar. Ahorra mucho tiempo, minimiza posibles errores y te pone en marcha rápidamente.

**Construcción DIY - Desde cero**  
Esta es la ruta de construcción fundamental sin atajos. Tu dispositivo descargará y configurará cada componente uno por uno. Aunque toma significativamente más tiempo y usa más batería que la opción precompilada, proporciona un control completo para desarrolladores o aquellos que necesitan una configuración profundamente personalizada.

---

#### :rocket: Opción A: Precompilado :rocket:

1. Abre Termux y ejecuta el siguiente comando. Esto instalará las herramientas base, luego descargará y extraerá automáticamente el sistema oficial IIAB precompilado para tu dispositivo:

    ```bash
    curl iiab.io/termux.txt | bash -s pull-rootfs
    ```

    > **Consejo:** Para instalar una imagen personalizada en su lugar, simplemente agrega su URL al final del comando
    > (ej., ...`bash -s pull-rootfs https://dominio.com/imagen_personalizada.tar.gz`).

2. Una vez que el proceso termine exitosamente, ¡tu instalación está completa!
    Para iniciarla, ejecuta:

    ```bash
    iiab-termux --login
    ```

    Y mira cómo inicia:

    ```bash
    ~ $ iiab-termux --login
    [iiab] Logging to: ~/.iiab-android/logs/iiab-termux.20260313.log
    [iiab] Wakelock acquired (termux-wake-lock).
    [iiab] Baseline stamp found: /data/data/com.termux/files/home/.iiab-android/stamp.termux_base
    [iiab] Entering IIAB Debian (via: iiab-termux --login)
    [iiab] Power-mode: enabled for this login session (persistent notification active).
    [pdsm:calibre-web] running
    [pdsm:kiwix] running
    [pdsm:kolibri] running
    [pdsm:mariadb] running
    [pdsm:nginx] running
    [pdsm:php-fpm] running
    root@localhost:~#
    ```

3. **Por favor, dirígete directamente a la sección [Probar tu instalación de IIAB](#probar-tu-instalación-de-iiab) a continuación.**

#### :train2: Opción B: Construcción DIY :train2:

1. Abre Termux y prepara el entorno completo:

    ```bash
    curl iiab.io/termux.txt | bash
    ```

2. Entra al entorno IIAB Debian de PRoot Distro:

    ```bash
    iiab-termux --login
    ```

3. Ejecuta el script del instalador. Esto configurará [`local_vars_android.yml`](https://wiki.iiab.io/go/FAQ#What_is_local_vars.yml_and_how_do_I_customize_it?) y lanzará el instalador principal de IIAB:

    ```bash
    iiab-android
    ```

    *Consejo: Como con cualquier instalación personalizada de IIAB, si el instalador falla o se interrumpe, siempre puedes reanudar desde donde se quedó ejecutando `iiab -f`.*

4. Si el instalador termina correctamente, verás un cuadro de texto que dice:

    > INTERNET-IN-A-BOX (IIAB) SOFTWARE INSTALL IS COMPLETE

---

### ⚠️ Notas especiales para usuarios de Android 12 y 13

Android 12 y 13 introdujeron una estricta limitación del sistema llamada ["Phantom Process Killer" (PPK)](https://github.com/agnostic-apollo/Android-Docs/blob/master/en/docs/apps/processes/phantom-cached-and-empty-processes.md). Si no se aborda, puede matar agresivamente las tareas en segundo plano o corromper tu instalación a la mitad (especialmente durante descargas largas o extracciones pesadas).

Para solucionar esto de manera segura, usamos una solución alternativa (workaround) integrada con ADB. Antes de elegir tu ruta de instalación arriba, por favor haz lo siguiente:

1. Ejecuta `iiab-termux --all` en Termux.
2. Asegúrate de aceptar los pasos de ADB Pair/Connect cuando se te solicite.
3. En las **Opciones de desarrollador** de tu Android, habilita la **Depuración inalámbrica**, selecciona **Vincular dispositivo con código de vinculación**, e ingresa el código de vinculación de vuelta en Termux.
4. *¿Necesitas ayuda?* Revisa este [video tutorial](https://iiab.switnet.org/android/vids/A15_mDNS_hb.mp4) para una guía visual. Una vez conectado a ADB, ¡nuestro script se encargará del workaround de PPK automáticamente para que tu instalación se ejecute sin problemas!

---

## Probar tu instalación de IIAB

Los [servicios `pdsm`](https://github.com/iiab/iiab/tree/master/roles/proot_services) de IIAB inician automáticamente después de la instalación. Para verificar que tus Apps de IIAB están funcionando (usando un navegador en tu dispositivo Android) visita estas URLs:

| App                       | URL                                                            |
|---------------------------|----------------------------------------------------------------|
| Calibre-Web               | [http://localhost:8085/books](http://localhost:8085/books)     |
| Kiwix (para archivos ZIM) | [http://localhost:8085/kiwix](http://localhost:8085/kiwix)     |
| Kolibri                   | [http://localhost:8085/kolibri](http://localhost:8085/kolibri) |
| IIAB Maps                 | [http://localhost:8085/maps](http://localhost:8085/maps)       |
| Matomo                    | [http://localhost:8085/matomo](http://localhost:8085/matomo)   |

Si encuentras un error o problema, por favor abre una [incidencia](https://github.com/iiab/iiab/issues) para que podamos ayudarte (y ayudar a otros) lo más rápido posible.

### Agregar un archivo ZIM

¡Ahora puedes poner una copia de Wikipedia (en casi cualquier idioma) en tu teléfono o tablet Android! Aquí te decimos cómo…

1. Navega al sitio: [download.kiwix.org/zim](https://download.kiwix.org/zim/)
2. Elige un archivo `.zim` (archivo ZIM) y copia su URL completa, por ejemplo:

   ```
   https://download.kiwix.org/zim/wikipedia/wikipedia_en_100_maxi_2026-01.zim
   ```

3. Abre la app Termux de Android y luego ejecuta:

   ```
   iiab-termux --login
   ```

   EXPLICACIÓN: Desde la línea de comandos (CLI, Command-Line Interface) de alto nivel de Termux, has "entrado vía shell" al CLI de bajo nivel de IIAB Debian en [PRoot Distro](https://wiki.termux.com/wiki/PRoot):

   ```text
          +----------------------------------+
          | Interfaz Android (Apps, Ajustes) |
          +-----------------+----------------+
                            |
                   abrir la | app Termux
                            v
              +-------------+------------+
              |   Termux (Android CLI)   |
              | $ iiab-termux --login    |
              +-------------+------------+
                            |
      "entrar vía shell" al | entorno de bajo nivel
                            v
      +---------------------+---------------------+
      |   proot-distro: IIAB Debian (userspace)   |
      | debian root# cd /opt/iiab/iiab            |
      +-------------------------------------------+
   ```

4. Entra a la carpeta donde IIAB guarda los archivos ZIM:

   ```
   cd /library/zims/content/
   ```

5. Descarga el archivo ZIM usando la URL que elegiste arriba, por ejemplo:

   ```
   wget https://download.kiwix.org/zim/wikipedia/wikipedia_en_100_maxi_2026-01.zim
   ```

6. Cuando termine la descarga, re-indexa los archivos ZIM de IIAB: (para que el nuevo ZIM aparezca para los usuarios, en la página http://localhost:8085/kiwix)

   ```
   iiab-make-kiwix-lib
   ```

   CONSEJO: Repite este último paso cuando elimines o agregues nuevos archivos ZIM en `/library/zims/content/`

## Acceso remoto

Aunque el teclado y la pantalla del teléfono son prácticos cuando estás en movimiento, acceder al entorno IIAB Debian de PRoot Distro desde una PC o laptop es muy útil para depuración. Puedes usar una conexión Wi-Fi existente o habilitar el hotspot nativo de Android si no hay una LAN inalámbrica disponible.

Antes de comenzar, obtén la IP de tu teléfono o tablet Android ejecutando `ifconfig` en Termux. O bien, obtén la IP revisando **Acerca del dispositivo → Estado** en los Ajustes de Android.

### SSH

Para iniciar sesión en IIAB en Android desde tu computadora, sigue estas instrucciones en CLI (línea de comandos) con SSH:

1. En tu teléfono o tablet Android, llega al CLI de Termux. **Si antes ejecutaste `iiab-termux --login` para entrar al CLI de bajo nivel de IIAB Debian en PRoot Distro — DEBES regresar al CLI de alto nivel de Termux — por ejemplo ejecutando:**

   ```
   exit
   ```

2. La forma más rápida de entrar por SSH a tu teléfono (o tablet) Android es poner una contraseña para el usuario de Termux. En el CLI de alto nivel de Termux, ejecuta:

   ```
   passwd
   ```

   Opcionalmente, la seguridad puede mejorar usando autenticación estándar por llaves SSH mediante el archivo `~/.ssh/authorized_keys`.

3. Inicia el servicio SSH. En la linea de comandos de alto nivel de Termux, ejecuta:

   ```
   sshd
   ```

   El servicio `sshd` puede automatizarse para iniciar cuando Termux se abre (ver [Termux-services](https://wiki.Termux.com/wiki/Termux-services)). Recomendamos hacer esto solo después de mejorar la seguridad de inicio de sesión usando llaves SSH.

4. Conéctate por SSH a tu teléfono Android.

   Desde tu laptop o PC, conectada a la misma red que tu teléfono Android, y conociendo la IP del teléfono (por ejemplo, `192.168.10.100`), ejecutarías:

   ```
   ssh -p 8022 192.168.10.100
   ```

   ¡No se necesita un nombre de usuario!

   Nota que el puerto **8022** se usa para SSH. Como Android se ejecuta sin permisos root, SSH no puede usar puertos con números menores. (Por la misma razón, el servidor web de IIAB [nginx] usa el puerto **8085** en lugar del puerto 80.)

### Iniciar sesión en el entorno de IIAB

Una vez que tengas una sesión SSH en tu dispositivo remoto, entra a PRoot Distro para acceder y ejecutar las aplicaciones de IIAB, igual que durante la instalación:

```
iiab-termux --login
```

Entonces estarás en una shell IIAB Debian con acceso a las herramientas del CLI (linea de comandos) de IIAB.

## ¿Qué pasa con los 32 bits?

¡IIAB en Android funciona en dispositivos antiguos de 32 bits, y estamos progresando! [**Maps ahora es compatible**](https://github.com/iiab/iiab/pull/4302).

Sin embargo, todavía hay limitaciones:

* Kiwix: Actualmente no es compatible en 32 bits.

Aunque nos encantaría cerrar [esta brecha](https://github.com/iiab/iiab-android/issues/35), portar Kiwix a esta arquitectura requiere importantes recursos de desarrollo. Como resultado, el desarrollo activo de esta función se encuentra actualmente en pausa. ¡Las contribuciones de la comunidad son bienvenidas si tienes la experiencia para ayudarnos a abordar esto!

Mientras tanto, puedes probar el estado actual de nuestro rootfs precompilado:

**Para dispositivos antiguos de 32 bits:**

```
curl iiab.io/termux.txt | bash -s pull-rootfs
```

Alternativamente, puedes seguir los pasos completos de construcción desde cero indicados en la sección [Elige tu ruta de instalación](#parte-2-elige-tu-ruta-de-instalación) arriba.

## Eliminación

Si quieres eliminar la instalación de IIAB y todas las apps asociadas, sigue estos pasos:

1. Elimina la instalación de IIAB y sus datos:

    ```bash
    iiab-termux --remove-rootfs
    ```

    > **Nota:** Todo el contenido de tu instalación de IIAB se eliminará al ejecutar este comando.
    > ¡Haz una copia de seguridad del contenido de tu biblioteca primero si planeas reinstalar más tarde!

2. Desinstala ambas apps de Android, **Termux** y **Termux:API**, si ya no las necesitas.

3. Desactiva las Opciones de desarrollador en los ajustes de tu Android, especialmente si solo las habilitaste para esta instalación.

## Uso Avanzado (`iiab-termux`)

Para usuarios avanzados, depuración o ajustes específicos del sistema, `iiab-termux` incluye varias herramientas integradas para copia de seguridad, restauración, vinculación ADB y algunas otras funciones útiles.

> **Consejo:** No olvides ejecutar `iiab-termux --update` de vez en cuando para obtener la última versión del script. Mantente atento al historial de nuestro repositorio para comprobar si hay nuevas funciones o cambios.

A continuación se muestra la salida de `iiab-termux --help`:

```text
Usage: iiab-termux [MODE] [OPTIONS]

=== CORE & INSTALL ===
  (no args)       Baseline + IIAB Debian bootstrap
  --all           Full setup: baseline, Debian, ADB, PPK, & checks
  --barebones     Minimal installation: Termux base + proxy (no rootfs)
  --login         Login into IIAB Debian
  --iiab-android  Install/update 'iiab-android' tool inside proot

=== ADB & SYSTEM TUNING ===
  --with-adb      Baseline + Debian + ADB wireless pair/connect
  --adb-only      Only ADB pair/connect (skips Debian)
  --connect-only  Connect to an already-paired device
  --ppk-only      Set max_phantom_processes=256 via ADB
  --check         Check Android readiness (Process restrictions, PPK)

=== BACKUP & RESTORE ===
  --backup-rootfs Backup IIAB Debian to .tar.gz
  --restore-rootfs Restore IIAB Debian from local .tar.gz
  --pull-rootfs   Download & restore rootfs from URL (P2P enabled)
  --remove-rootfs Delete IIAB Debian rootfs and all data

=== PROXY (BOXYPROXY) ===
  --proxy-start   Start background proxy
  --proxy-stop    Stop background proxy
  --proxy-status  Show proxy status

Options:
  --connect-port [P]  Skip CONNECT PORT prompt
  --timeout [SECS]    Wait time per prompt (default 180)
  --no-meta4          Disable Metalink/P2P for --pull-rootfs
  --keep-tarball      Keep the downloaded archive after --pull-rootfs
  --reset-iiab        Reinstall IIAB Debian
  --install-self      Install the current script to Termux bin path
  --welcome           Show the welcome screen
  --debug             Enable extra logs
  --help, --version   Show this help or version

Notes: Setup on Android 12 & 13 requires ADB due to OS design. 14+ simplifies this with system UI toggles
```
