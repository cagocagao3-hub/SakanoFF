# Sakano FF 3.2 — captura com três modos de áudio

## Modos
- **Sem áudio:** grava somente a tela pelo MediaRecorder.
- **Áudio interno:** captura reprodução do Android com AudioPlaybackCapture, codifica vídeo H.264 e áudio AAC e combina as faixas MP4 com MediaMuxer.
- **Microfone:** grava a tela com áudio do microfone pelo MediaRecorder.

## Compatibilidade e limites
- Áudio interno exige Android 10 (API 29) ou superior e a permissão de gravação de áudio.
- O aplicativo/jogo reproduzido precisa permitir captura de áudio. Alguns conteúdos protegidos ou aplicativos que bloqueiam captura podem resultar em áudio silencioso.
- O áudio interno usa codificação H.264/AAC por hardware quando disponível; a carga de CPU/GPU e o consumo variam conforme o aparelho, resolução e FPS.
- Pausar a captura interna interrompe a leitura de áudio e desconecta temporariamente a superfície de vídeo.

## Perfil leve recomendado
- 720p / 30 FPS
- Qualidade Normal ou Econômica
- Sem áudio quando a prioridade absoluta for reduzir processamento e tamanho do arquivo.
