# Mass Patcher

Um app Android simples de teste para modificar e patchar APKs, baseado no Lucky Patcher.

## Funcionalidades

- ✅ Selecionar arquivos APK do dispositivo
- ✅ Aplicar patches simples aos APKs
- ✅ Modificar recursos e manifesto do APK
- ✅ Salvar APK patcheado em local acessível
- ✅ Interface simples e intuitiva

## Estrutura do Projeto

```
Mass/
├── app/
│   ├── src/main/
│   │   ├── kotlin/com/mass/patcher/
│   │   │   ├── MainActivity.kt
│   │   │   └── service/
│   │   │       └── ApkPatcherService.kt
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   ├── values/
│   │   │   ├── drawable/
│   │   │   └── xml/
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── build.gradle
└── settings.gradle
```

## Requisitos

- Android 8.0+ (API 26)
- Kotlin 1.8.0
- Gradle 8.0.0

## Como Usar

1. Clone ou compile o projeto
2. Execute no Android Studio ou via linha de comando:
   ```bash
   ./gradlew assembleDebug
   ```
3. Instale o APK no dispositivo
4. Abra o app e selecione um APK
5. Digite um nome para o patch
6. Clique em "Patchar APK"
7. Abra o APK patcheado diretamente do app

## Dependências

- androidx.appcompat:appcompat
- com.google.android.material:material
- org.jetbrains.kotlinx:kotlinx-coroutines
- commons-io:commons-io
- com.android.tools:apksig

## Notas de Desenvolvimento

Este é um app de teste e demonstração. A funcionalidade de patching é básica e modifica apenas:
- Adiciona metadata aos recursos
- Insere comentários no manifesto

Para fins educacionais apenas. Use responsavelmente!
