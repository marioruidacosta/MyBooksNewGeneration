# Google Play store listing

Texts to paste into Play Console › Store presence › Main store listing, and the images to upload with them.
The app itself is in English; the Portuguese listing says so.

## Images

| File | Play Console field |
|---|---|
| `icon-512.png` | App icon (512 × 512) |
| `feature-graphic-1024x500.png` | Feature graphic (1024 × 500) |
| `screenshots/01-home.png` … `06-activity.png` | Phone screenshots (1080 × 1920), in this order |

The screenshots show the sample library of the screen tests, not anyone's real books. To make them again after a
change to the app: run the screen tests (`./gradlew :androidApp:testDebugUnitTest`), then open each page in
`source/` in a browser at 1080 × 1920 (or 1024 × 500, 512 × 512) and save a screenshot, for example with headless
Edge or Chrome: `msedge --headless=new --hide-scrollbars --window-size=1080,1920 --screenshot=01-home.png source/01-home.html`.

## English (en-GB)

**App name** (30 max)

> MyBooks NG

**Short description** (80 max)

> Your personal library: track your reading, scan books, set challenges.

**Full description** (4000 max)

> MyBooks NG is a personal library for the books you read, want to read and want to buy. Your library lives on your
> phone, with no ads and no tracking.
>
> TRACK YOUR READING
> • Shelves for Reading, To read, Read and To buy, sorted and searched the way you like
> • Progress by page or percentage, reading dates, scores and private notes
> • Tags, series, and the books you've lent out
> • Quotes: type them, or photograph the page and keep the passage
>
> ADD BOOKS FAST
> • Scan a barcode, or a whole pile of books in one go
> • Details and covers from Google Books, Open Library and the National Library of Portugal
> • Import and export your Goodreads library
>
> YOUR READING YEAR
> • A yearly reading challenge that shows your pace
> • Statistics: books and pages per month, top authors and publishers
> • Your year in review, ready to share as a picture
> • A home-screen widget with your current book and your challenge
>
> DISCOVER
> • Suggestions based on your shelves
> • New in Portugal: each month's new books, by genre, author or publisher, with a monthly notification when your
>   authors have something new
> • Where to buy, in Portuguese bookshops
>
> WITH FRIENDS (OPTIONAL)
> • Share your reading with friends and see their activity, scores and reviews
> • Everything else works without an account
>
> Your library is included in your phone's backup, and you can export it whenever you like.

**What's new** (first release, 500 max)

> First release: shelves, reading progress and challenges, barcode scanning (one book or many), quotes, tags,
> series, loans, statistics, year in review, a home-screen widget, and New in Portugal.

## Português (pt-PT)

**Nome da app**

> MyBooks NG

**Descrição breve** (80 no máximo)

> A sua biblioteca pessoal: leituras, livros digitalizados e desafios de leitura.

**Descrição completa**

> O MyBooks NG é uma biblioteca pessoal para os livros que lê, quer ler e quer comprar. A sua biblioteca fica no
> telemóvel, sem anúncios e sem rastreio. A aplicação está em inglês.
>
> ACOMPANHE AS SUAS LEITURAS
> • Estantes A ler, Para ler, Lidos e Para comprar, ordenadas e pesquisadas como preferir
> • Progresso por página ou percentagem, datas de leitura, classificações e notas privadas
> • Etiquetas, séries e os livros que emprestou
> • Citações: escreva-as ou fotografe a página e guarde o excerto
>
> ADICIONE LIVROS DEPRESSA
> • Leia o código de barras de um livro, ou de uma pilha de livros de seguida
> • Dados e capas do Google Books, Open Library e Biblioteca Nacional de Portugal
> • Importe e exporte a sua biblioteca do Goodreads
>
> O SEU ANO DE LEITURAS
> • Um desafio de leitura anual que mostra o seu ritmo
> • Estatísticas: livros e páginas por mês, autores e editoras mais lidos
> • O resumo do seu ano, pronto a partilhar como imagem
> • Um widget no ecrã principal com o livro que está a ler e o seu desafio
>
> DESCUBRA
> • Sugestões com base nas suas estantes
> • Novidades em Portugal: os livros novos de cada mês, por género, autor ou editora, com uma notificação mensal
>   quando os seus autores têm livro novo
> • Onde comprar, em livrarias portuguesas
>
> COM AMIGOS (OPCIONAL)
> • Partilhe as suas leituras com amigos e veja a atividade, as classificações e as críticas deles
> • Tudo o resto funciona sem conta
>
> A sua biblioteca entra na cópia de segurança do telemóvel, e pode exportá-la sempre que quiser.

**Novidades**

> Primeira versão: estantes, progresso e desafios de leitura, leitura de códigos de barras (um livro ou vários),
> citações, etiquetas, séries, empréstimos, estatísticas, resumo do ano, widget e Novidades em Portugal.

## Other Play Console answers

- Category: Books & Reference. Tags: reading, books, library.
- Contact email: mario.rui.dacosta@gmail.com. Website: https://marioruidacosta.github.io/MyBooksNewGeneration/
- Privacy policy: https://marioruidacosta.github.io/MyBooksNewGeneration/privacy.html
- Data safety and account deletion: see [../docs/play-release.md](../docs/play-release.md).
