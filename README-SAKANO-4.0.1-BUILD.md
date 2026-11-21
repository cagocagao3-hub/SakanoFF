# Sakano FF 4.0.1

## O que esta versão entrega
- Menu Atualizações para consulta manual.
- Verificação automática de versão ao abrir o app.
- Tela de atualização obrigatória quando uma Release mais recente contém APK.
- APK assinado com os segredos persistentes SAKANO_KEYSTORE_BASE64, SAKANO_STORE_PASSWORD, SAKANO_KEY_ALIAS e SAKANO_KEY_PASSWORD.
- GitHub Actions publica o APK como artefato do workflow e como asset da GitHub Release.
- versionCode 41 / versionName 4.0.1.

## Como gerar
Envie o conteúdo deste ZIP para a raiz da branch `main` do repositório `cagocagao3-hub/SakanoFF`. O workflow `.github/workflows/android.yml` executa `assembleRelease`, valida a assinatura, publica o APK no resumo de Artifacts e cria a Release `v4.0.1`.

## Onde baixar
No GitHub: Actions > execução concluída > Artifacts > SakanoFF-4.0.1.
Ou: Releases > Sakano FF 4.0.1 > SakanoFF-4.0.1.apk.

A instalação de APK é sempre confirmada pelo Android. Para atualizar uma instalação existente, o APK precisa manter o mesmo applicationId e certificado de assinatura.
