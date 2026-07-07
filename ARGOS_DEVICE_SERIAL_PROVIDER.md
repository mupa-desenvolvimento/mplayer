# Contrato: Argos expõe o serial de hardware para o MPlayer

## Por quê

Em Android 10+ (API 29+), `Build.getSerial()` só funciona para apps que são **Device Owner**
ou possuem `READ_PRIVILEGED_PHONE_STATE` (permissão restrita a apps de sistema/privilegiados).
O **Argos** (`com.mupa.agent.argos`) é Device Owner nos dispositivos gerenciados e consegue ler
o serial real de hardware (o mesmo que aparece em `adb devices`). O **MPlayer**
(`com.mupa.player.enterprise`) é um app comum e não consegue.

Sem esse serial real, o MPlayer gera seu próprio `persistent_device_id` a partir do `ANDROID_ID`
(ou de uma propriedade de sistema, quando acessível), que **não bate** com o serial que o Argos
usa para identificar o dispositivo nos comandos MDM (`m_argos/devices/{device_id}` no protocolo
documentado em `ARGOS_PLATFORM_AGENT_PROTOCOL.md`). Isso causa dois "IDs de dispositivo"
diferentes para o mesmo aparelho físico, dependendo do app.

## O que o MPlayer já implementa (lado consumidor)

Em `DeviceIdentityManager.kt`, antes de qualquer outra estratégia de resolução de ID, o MPlayer
consulta um `ContentProvider` do Argos:

```kotlin
val uri = Uri.parse("content://com.mupa.agent.argos.provider.device/serial")
context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
    if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndex("serial"))
}
```

Se a query falhar (Argos não instalado, provider não implementado, qualquer exceção), o MPlayer
cai de volta no comportamento atual (propriedades de sistema Zebra → `Build.getSerial()` →
`ANDROID_ID`). **Não há nenhuma dependência rígida** — é puramente um "se disponível, use".

O `AndroidManifest.xml` do MPlayer já declara a visibilidade necessária (Android 11+ package
visibility):

```xml
<queries>
    <package android:name="com.mupa.agent.argos" />
    <provider android:authorities="com.mupa.agent.argos.provider.device" />
</queries>
```

## O que falta implementar no Argos (lado provedor)

No projeto do Argos, criar um `ContentProvider` simples, somente leitura, expondo o serial via
uma única linha/coluna:

- **Authority:** `com.mupa.agent.argos.provider.device`
- **Path consultado pelo MPlayer:** `/serial`
- **Coluna esperada:** `serial` (string) — o mesmo valor que o Argos já usa como `persistent_device_id`
  / identidade canônica no protocolo `m_argos`.
- **Permissão:** nenhuma exigida (read-only, sem dado sensível — é o mesmo serial que já aparece
  em `adb devices` e em qualquer inventário MDM). Se quiser restringir, pode usar
  `android:permission` com uma permissão `signature`-level compartilhada, mas então o MPlayer
  precisaria declarar/possuir essa permissão também (avisar se for esse o caminho).

Exemplo mínimo de implementação:

```kotlin
class DeviceInfoProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor {
        val serial = /* a mesma lógica que o Argos já usa para resolver o serial real */
        return MatrixCursor(arrayOf("serial")).apply { addRow(arrayOf(serial)) }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}
```

E declarar no `AndroidManifest.xml` do Argos:

```xml
<provider
    android:name=".provider.DeviceInfoProvider"
    android:authorities="com.mupa.agent.argos.provider.device"
    android:exported="true"
    android:readPermission="" />
```

## Validação depois de implementado

Com os dois apps instalados no mesmo dispositivo:

```bash
adb shell content query --uri content://com.mupa.agent.argos.provider.device/serial
```

Deve retornar uma linha com a coluna `serial` igual ao serial reportado por `adb devices -l`.
Depois, reinstalar/limpar dados do MPlayer e confirmar que o `device_id` usado no cadastro
passa a bater com esse serial.
