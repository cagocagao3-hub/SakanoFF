# Sakano FF 4.0 — Atualizador automático

## Estrutura do repositório

Este ZIP já está organizado para ser extraído **diretamente na raiz** do repositório GitHub `cagocagao3-hub/SakanoFF`.

Depois de enviar os arquivos, a estrutura deve ficar assim:

- `.github/workflows/android.yml`
- `app/build.gradle`
- `app/src/main/...`
- `build.gradle`
- `settings.gradle`

Não deixe tudo dentro de uma pasta adicional chamada `Sakano FF`, pois o GitHub Actions precisa encontrar `.github/workflows/android.yml` na raiz.

## Segredos já cadastrados

O workflow utiliza estes GitHub Actions Secrets:

- `SAKANO_KEYSTORE_BASE64`
- `SAKANO_STORE_PASSWORD`
- `SAKANO_KEY_ALIAS`
- `SAKANO_KEY_PASSWORD`

O alias configurado no projeto é `sakano`. Não altere a senha nem perca o arquivo privado `sakano-release.jks`. Guarde uma cópia segura fora do celular também. Nunca envie esse arquivo ou as senhas para o repositório.

## Como publicar atualizações futuras

1. Faça as alterações no projeto.
2. Em `app/build.gradle`, aumente **versionCode** e **versionName** a cada nova versão.
3. Envie os arquivos para a branch `main`.
4. O GitHub Actions compila, verifica a assinatura e publica o APK numa GitHub Release.

Exemplo para uma próxima versão: `versionCode 41` e `versionName '4.1'`. Não reutilize uma versão já publicada.

## Como o atualizador funciona

- Consulta a última GitHub Release ao iniciar.
- Se encontrar uma versão superior, apresenta a tela de atualização.
- Baixa o APK da Release e abre o instalador do Android.
- Se necessário, pede que o usuário permita ao Sakano FF instalar apps desconhecidos nas configurações do Android.
- O Android sempre exige confirmação do usuário; o app não instala atualizações silenciosamente.

Se não houver internet ou a API do GitHub estiver indisponível, o aplicativo abre normalmente. Assim, a verificação não impede o uso offline.

## Migração da assinatura

O pacote continua sendo `com.sakano.ff`. Para atualizar por cima, o APK instalado e o novo APK precisam ter sido assinados com a mesma chave e a versão nova precisa ter `versionCode` maior.

Se uma versão anterior foi assinada com uma chave temporária diferente, pode ser necessário fazer backup dos vídeos, desinstalar essa versão uma única vez e instalar a 4.0 assinada com a chave permanente. Depois dessa migração, mantenha a mesma chave em todas as versões.

## Importante

Este pacote foi revisado e ajustado estruturalmente, mas a compilação Android e a instalação em um aparelho físico precisam ser validadas no GitHub Actions e no celular. Não considere a atualização publicada até o workflow terminar com sucesso e a Release conter o APK.
