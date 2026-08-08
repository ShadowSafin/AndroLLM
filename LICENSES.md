# License Information

## AndroLLM — Apache License 2.0

Copyright 2026 AndroLLM Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Full license text: See [LICENSE.md](LICENSE.md)

---

## Third-Party Licenses

AndroLLM incorporates code and dependencies from numerous open-source projects.
Below is a summary of the major components and their licenses.

### Core Dependencies

| Component | License | Notes |
|---|---|---|
| Android SDK / Jetpack Compose | Apache 2.0 | Google |
| Kotlin | Apache 2.0 | JetBrains |
| Dagger / Hilt | Apache 2.0 | Google |
| Room | Apache 2.0 | Google |
| DataStore | Apache 2.0 | Google |
| Navigation Compose | Apache 2.0 | Google |
| Coroutines | Apache 2.0 | JetBrains |
| kotlinx.serialization | Apache 2.0 | JetBrains |
| Timber | Apache 2.0 | Jake Wharton |
| Coil | Apache 2.0 | Sean McQuillan |
| Accompanist | Apache 2.0 | Google |

### Native Libraries

| Component | License | Notes |
|---|---|---|
| llama.cpp (vendored) | MIT | Georgi Gerganov et al. |
| ggml (Vulkan backend) | MIT | Part of llama.cpp |
| Vulkan headers/SDK | Apache 2.0, MIT | Khronos Group |

### Voice Libraries

| Component | License | Notes |
|---|---|---|
| sherpa-onnx | Apache 2.0 | k2-fsa |
| ONNX Runtime Mobile | Apache 2.0 | Microsoft |
| Piper (VITS-LJSpeech) | MIT | r9y9 et al. |

### Networking

| Component | License | Notes |
|---|---|---|
| Ktor | Apache 2.0 | JetBrains |
| OkHttp | Apache 2.0 | Square |
| Retrofit | Apache 2.0 | Square |

### Authentication

| Component | License | Notes |
|---|---|---|
| Firebase Auth | Apache 2.0 | Google |
| Credential Manager | Apache 2.0 | Google |
| Google Identity Services | Apache 2.0 | Google |

### Testing

| Component | License | Notes |
|---|---|---|
| JUnit | Eclipse Public License 2.0 | |
| mockk | MIT | |
| Turbine | Apache 2.0 | Cash App |
| Robolectric | MIT | |

### Code Quality

| Component | License | Notes |
|---|---|---|
| Spotless | Apache 2.0 | Francis Konzie |
| Detekt | Apache 2.0 | Artur Bosch |

### Models (User-Downloaded)

Model files are subject to their individual licenses, which vary by model:

| Model Family | Typical License |
|---|---|
| Meta Llama | Llama 3 Community License |
| Google Gemma | Gemma Terms |
| Alibaba Qwen | Qwen License (Apache 2.0 variant) |
| DeepSeek | DeepSeek License |
| Mistral | Mistral Research License |

Users are responsible for complying with individual model licenses.
The app displays license information in the model detail screen.

---

## Complete Dependency Licenses

For a complete list of all transitive dependencies and their licenses, run:

```bash
./gradlew app:dependencies --configuration releaseRuntimeClasspath
```

Or inspect the generated license report (when Maven Publish plugin is configured):
```bash
./gradlew generateReleaseLicenseReport
```
