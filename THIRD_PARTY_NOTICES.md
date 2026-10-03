# Third-party notices

RearCue includes or downloads the following third-party components for Voice Broadcast:

- **Sherpa-ONNX** (`com.k2fsa.sherpa.onnx`, JNI libraries and Kotlin API): Apache License 2.0.  
  Source: https://github.com/k2-fsa/sherpa-onnx
- **Kokoro-82M / kokoro-multi-lang-v1_0 model package**: Apache License 2.0 for model weights and bundled model resources.  
  Source: https://huggingface.co/hexgrad/Kokoro-82M  
  Model release: https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
- **Apache Commons Compress** (runtime download/extraction): Apache License 2.0.  
  Source: https://commons.apache.org/proper/commons-compress/

The offline model is downloaded only after the user explicitly enables Voice Broadcast and confirms the download. The model package's included license files are preserved with the downloaded resources.
