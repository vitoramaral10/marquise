<div align="center">

<img src=".github/icon.png" width="112" alt="">

# Marquise

**Uma tela inicial sem anúncios para Google TV, feita de pôsteres e montada em volta da sua biblioteca Jellyfin.**

O que acabou de chegar no seu servidor, logo na frente. Seus apps, nas cores deles. Um botão para voltar.

[Instalar](#instalar) · [Configurações](#configurações) · [Voltar ao original](#voltar-ao-original) · [Outras TVs](#outras-tvs) · [English](README.md)

<img src=".github/screenshot.png" width="880" alt="Marquise: saudação com hora e clima, seis pôsteres adicionados recentemente ao Jellyfin e uma fileira de apps na cor de cada marca">

</div>

## O que você ganha

- **Sua biblioteca, não um feed de propaganda.** Seis pôsteres do que acabou de entrar no seu Jellyfin, com ano, nota
  e classificação indicativa, mudando a cada meia hora. Filmes e Séries têm abas próprias.
- **Um clique para assistir.** Escolha um pôster e ele abre o filme ou a série no app do Jellyfin.
- **Seus apps, nas cores deles.** Você escolhe quais apps ficam na fileira e em que ordem. A TV aberta pode ser um deles.
- **Hora e clima** no topo, e a previsão das próximas horas com um clique para baixo.
- **Claro de dia, escuro à noite**, seguindo o pôr do sol de verdade da sua cidade.
- **Tela de descanso combinando**: as imagens dos títulos novos em tela cheia, com um relógio grande.
- **Sem anúncios, sem fileiras patrocinadas, sem rastreamento.** Só conversa com o seu Jellyfin e com o Open-Meteo
  para o clima.
- **Seguro.** Sem root. Nada é desinstalado. *Original* traz de volta o Início de fábrica, e um vigia faz isso sozinho
  se o Marquise travar.

## Requisitos

- Uma **Google TV ou Android TV com Android 12 ou mais novo** (TCL, Sony, Hisense, Philips, Chromecast com Google TV,
  Google TV Streamer, onn 4K, NVIDIA Shield…). Samsung, LG ou outra marca: ligue um Google TV Streamer nela.
- Um **servidor Jellyfin** que a TV alcance, e o **app do Jellyfin** na TV.
- Um computador na mesma rede, com JDK 17+, as ferramentas de linha de comando do Android SDK e `adb`.

## Instalar

Uns dez minutos.

1. **Na TV**, ative o modo desenvolvedor: *Configurações → Sistema → Sobre →* aperte 7 vezes em *Build do Android TV
   OS*. Depois *Configurações → Sistema → Opções do desenvolvedor →* ative **Depuração USB**. Anote o IP da TV
   (*Configurações → Rede*).
2. **Instale as ferramentas.** No Mac:
   ```bash
   brew install --cask temurin android-commandlinetools android-platform-tools
   sdkmanager "build-tools;34.0.0" "platforms;android-34"
   ```
   No Linux, instale OpenJDK 17, as ferramentas de linha de comando do Android e o `adb` pelo gerenciador de pacotes, e
   rode a mesma linha do `sdkmanager`. No Windows, use o WSL.
3. **Baixe o código e compile uma vez** (isso cria o `config.properties`):
   ```bash
   git clone https://github.com/vitoramaral10/marquise && cd marquise
   ./build.sh
   ```
4. **Preencha o `config.properties`**: o endereço do Jellyfin e uma chave de API (*Painel do Jellyfin → Chaves de API
   → +*), as coordenadas da sua cidade e os apps da fileira. Veja [Configurações](#configurações). Compile de novo:
   `./build.sh`.
5. **Instale:**
   ```bash
   adb connect 192.168.1.50:5555      # o IP da sua TV; aceite o aviso na TV e marque "Sempre permitir"
   adb install -r marquise.apk
   ```
   Em algumas TVs TCL, libere também o funcionamento em segundo plano:
   `adb shell appops set br.dev.vitormelo.marquise AUTO_START allow`.
6. **Na TV**, abra *Marquise → Home Screen → Marquise* e aceite mais uma vez o aviso de depuração (o Marquise usa a
   depuração da própria TV para trocar o Início).

> Prefere que uma IA faça? Abra a pasta no [Claude Code](https://claude.com/claude-code) e diga *"Instale o Marquise na
> minha TV em 192.168.1.50. Meu Jellyfin está em http://192.168.1.10:8096. Moro em Curitiba."* O
> [CLAUDE.md](CLAUDE.md) explica o caminho.

## Configurações

Tudo fica no `config.properties`, criado no primeiro build e fora do git.

| Chave | O que faz | Padrão |
|---|---|---|
| `jellyfin_url` | Seu servidor como a TV o alcança, ex. `http://192.168.1.10:8096` | vazio (sem pôsteres) |
| `jellyfin_key` | Uma chave de API do Jellyfin | vazio |
| `jellyfin_user` | De quem é o "adicionados recentemente" (bibliotecas e controle parental) | o primeiro usuário |
| `shelf` | A fileira de apps, em ordem: `jellyfin`, `live`, `youtube`, `netflix`, `prime`, `disney`, `max`, `appletv`, `hulu`, `paramount`, `peacock`, `globoplay`, `spotify`, `f1tv` | `jellyfin,live,youtube,netflix,prime,disney,max,spotify` |
| `latitude`, `longitude` | Sua cidade, para o clima e o pôr do sol | Nova York |
| `city` | O nome no cartão do clima | `Clima` |
| `language` | `auto` (segue a TV), `en` ou `pt` | `auto` |
| `temperature` | `C`, `F` ou `auto` | `auto` |
| `disable_assistant` | Também desliga o Google Assistente (o botão do microfone para) | `false` |
| `extra_disable` | Mais pacotes para desligar enquanto o Marquise estiver ligado, separados por vírgula | vazio |

Mudou algo? Rode `./build.sh` e instale de novo. **Guarde o `marquise.keystore`**: as atualizações precisam da mesma
chave.

Um endereço HTTP sem criptografia para o Jellyfin funciona na sua rede: o build libera tráfego sem criptografia
**só** para esse endereço.

## Voltar ao original

Na TV: *Marquise → Home Screen → Original*. O Início de fábrica, a tela de descanso e todos os apps voltam exatamente
como estavam. Se o Início do Marquise travar três vezes em cinco minutos, ele volta sozinho.

Para remover de vez, primeiro mude para *Original* e depois desinstale o Marquise (*Configurações → Apps*).

## O que ele muda

- Vira a tela inicial e a tela de descanso.
- Desliga, sem desinstalar:
  - o Início e a tela de descanso de fábrica;
  - o Início de recuperação do Google TV, Play Games, YouTube Music, Play Auto Installs (instalação de apps
    patrocinados) e os relatórios de feedback;
  - nas TVs TCL, o TCL Channel, mensagens, modo demonstração, menu de hotel, manuais, espelhamento, logs e apps de
    propaganda;
  - o Google Assistente, só com `disable_assistant=true`, e o que você listar em `extra_disable`.
- Imagem, som, entradas, pareamento do controle, serviços do Google e a Play Store ficam numa lista protegida,
  conferida antes de cada mudança.

Em algumas TVs os botões de atalho do controle pertencem ao Início de fábrica (na TCL: Netflix, YouTube, Prime), então
ficam parados enquanto o Marquise estiver ligado.

## Outras TVs

O Marquise vem com as listas para Google TV e TCL. Para outra marca, acrescente os apps de propaganda dela em `DEBLOAT`
e as peças de sistema em `PROTECTED` no [`HomeMode.java`](app/src/br/dev/vitormelo/marquise/HomeMode.java), e o app de
TV ao vivo em `LIVE_TV_APPS` no [`HomeApps.java`](app/src/br/dev/vitormelo/marquise/HomeApps.java). O
[CLAUDE.md](CLAUDE.md) lista os comandos `adb` de leitura que mostram o que sua TV tem. Pull requests com outras marcas
são muito bem-vindos.

## Como funciona

- **Um app Android simples em Java, sem Gradle e sem bibliotecas.** O [`build.sh`](build.sh) roda aapt2 → javac → d8 →
  zipalign → apksigner e gera um APK de ~1 MB.
- **Ele usa o ADB da própria TV, de dentro da TV.** O app se conecta a `127.0.0.1:5555` com a própria chave e roda os
  mesmos comandos que você rodaria: `cmd package set-home-activity`, `settings put secure screensaver_components`,
  `pm disable-user`. Nada fica escutando na rede.
- **Desenhado à mão num único Canvas**, para manter 60 fps no chip modesto da TV.
- **Uma rede de segurança**: um vigia num processo separado devolve o Início de fábrica depois de três travamentos em
  cinco minutos, e uma conferência no boot mantém sua escolha depois de atualizações da TV.

## Privacidade

Sem contas, sem analytics, sem rastreamento. O Marquise conversa com o seu Jellyfin (com a chave que você definir) e
com o Open-Meteo (com as coordenadas que você definir). Suas configurações ficam no seu computador e na sua TV.

## Créditos

- Clima por [Open-Meteo.com](https://open-meteo.com/) (CC BY 4.0).
- Ícones: [Material Symbols](https://fonts.google.com/icons) (Apache 2.0). Fonte: [Inter](https://rsms.me/inter/)
  (SIL OFL 1.1).
- Sem vínculo com Jellyfin, Google, TCL ou qualquer serviço de streaming. Nomes e logos de apps pertencem aos donos.

## Licença

[MIT](LICENSE).
