/**
 * Lines from books, one a day. Each is quoted as written in a work old enough to be free to quote at length (or as
 * the long-standing public-domain translation), with the work it comes from. `wiki` names the author's Wikipedia
 * article (language:Title), whose freely licensed lead image is the portrait.
 */
export type BookQuote = {
  text: string
  author: string
  work: string
  /** The language of the text, for the page's `lang` attribute. */
  lang: string
  wiki: string
}

export const QUOTES: readonly BookQuote[] = [
  { text: 'Tudo vale a pena\nSe a alma não é pequena.', author: 'Fernando Pessoa', work: 'Mensagem', lang: 'pt', wiki: 'pt:Fernando_Pessoa' },
  { text: 'It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.', author: 'Jane Austen', work: 'Pride and Prejudice', lang: 'en', wiki: 'en:Jane_Austen' },
  { text: 'Amor é fogo que arde sem se ver;\nÉ ferida que dói, e não se sente;', author: 'Luís de Camões', work: 'Rimas', lang: 'pt', wiki: 'pt:Luís_de_Camões' },
  { text: 'It was the best of times, it was the worst of times.', author: 'Charles Dickens', work: 'A Tale of Two Cities', lang: 'en', wiki: 'en:Charles_Dickens' },
  { text: 'I am no bird; and no net ensnares me: I am a free human being with an independent will.', author: 'Charlotte Brontë', work: 'Jane Eyre', lang: 'en', wiki: 'en:Charlotte_Brontë' },
  { text: 'Ser poeta é ser mais alto, é ser maior\nDo que os homens!', author: 'Florbela Espanca', work: 'Charneca em Flor', lang: 'pt', wiki: 'pt:Florbela_Espanca' },
  { text: 'Beware; for I am fearless, and therefore powerful.', author: 'Mary Shelley', work: 'Frankenstein', lang: 'en', wiki: 'en:Mary_Shelley' },
  { text: 'A woman must have money and a room of her own if she is to write fiction.', author: 'Virginia Woolf', work: 'A Room of One’s Own', lang: 'en', wiki: 'en:Virginia_Woolf' },
  { text: 'Não sou nada.\nNunca serei nada.\nNão posso querer ser nada.\nÀ parte isso, tenho em mim todos os sonhos do mundo.', author: 'Álvaro de Campos (Fernando Pessoa)', work: 'Tabacaria', lang: 'pt', wiki: 'pt:Fernando_Pessoa' },
  { text: 'There is no such thing as a moral or an immoral book. Books are well written, or badly written. That is all.', author: 'Oscar Wilde', work: 'The Picture of Dorian Gray', lang: 'en', wiki: 'en:Oscar_Wilde' },
  { text: 'Why, sometimes I’ve believed as many as six impossible things before breakfast.', author: 'Lewis Carroll', work: 'Through the Looking-Glass', lang: 'en', wiki: 'en:Lewis_Carroll' },
  { text: '“Hope” is the thing with feathers\nThat perches in the soul.', author: 'Emily Dickinson', work: 'Poems', lang: 'en', wiki: 'en:Emily_Dickinson' },
  { text: 'Do I contradict myself?\nVery well then I contradict myself,\n(I am large, I contain multitudes.)', author: 'Walt Whitman', work: 'Song of Myself', lang: 'en', wiki: 'en:Walt_Whitman' },
  { text: 'Ao verme que primeiro roeu as frias carnes do meu cadáver dedico como saudosa lembrança estas memórias póstumas.', author: 'Machado de Assis', work: 'Memórias Póstumas de Brás Cubas', lang: 'pt', wiki: 'pt:Machado_de_Assis' },
  { text: 'There are more things in heaven and earth, Horatio,\nThan are dreamt of in your philosophy.', author: 'William Shakespeare', work: 'Hamlet', lang: 'en', wiki: 'en:William_Shakespeare' },
  { text: 'All that we see or seem\nIs but a dream within a dream.', author: 'Edgar Allan Poe', work: 'A Dream Within a Dream', lang: 'en', wiki: 'en:Edgar_Allan_Poe' },
  { text: 'So we beat on, boats against the current, borne back ceaselessly into the past.', author: 'F. Scott Fitzgerald', work: 'The Great Gatsby', lang: 'en', wiki: 'en:F._Scott_Fitzgerald' },
  { text: 'As armas e os barões assinalados\nQue da ocidental praia Lusitana\nPor mares nunca de antes navegados\nPassaram ainda além da Taprobana,', author: 'Luís de Camões', work: 'Os Lusíadas', lang: 'pt', wiki: 'pt:Luís_de_Camões' },
  { text: 'I went to the woods because I wished to live deliberately, to front only the essential facts of life.', author: 'Henry David Thoreau', work: 'Walden', lang: 'en', wiki: 'en:Henry_David_Thoreau' },
  { text: 'The growing good of the world is partly dependent on unhistoric acts.', author: 'George Eliot', work: 'Middlemarch', lang: 'en', wiki: 'en:George_Eliot' },
  { text: 'Happy families are all alike; every unhappy family is unhappy in its own way.', author: 'Leo Tolstoy', work: 'Anna Karenina (tr. Constance Garnett)', lang: 'en', wiki: 'en:Leo_Tolstoy' },
  { text: 'Midway upon the journey of our life\nI found myself within a forest dark.', author: 'Dante Alighieri', work: 'Inferno (tr. Henry Wadsworth Longfellow)', lang: 'en', wiki: 'en:Dante_Alighieri' },
  { text: 'On ne voit bien qu’avec le cœur. L’essentiel est invisible pour les yeux.', author: 'Antoine de Saint-Exupéry', work: 'Le Petit Prince', lang: 'fr', wiki: 'fr:Antoine_de_Saint-Exupéry' },
  { text: 'Call me Ishmael.', author: 'Herman Melville', work: 'Moby-Dick', lang: 'en', wiki: 'en:Herman_Melville' },
  { text: 'A book must be the axe for the frozen sea inside us.', author: 'Franz Kafka', work: 'Letter to Oskar Pollak, 1904', lang: 'en', wiki: 'en:Franz_Kafka' },
  { text: 'Whatever our souls are made of, his and mine are the same.', author: 'Emily Brontë', work: 'Wuthering Heights', lang: 'en', wiki: 'en:Emily_Brontë' },
  { text: 'A thing of beauty is a joy for ever.', author: 'John Keats', work: 'Endymion', lang: 'en', wiki: 'en:John_Keats' },
  { text: 'I’m not afraid of storms, for I’m learning how to sail my ship.', author: 'Louisa May Alcott', work: 'Little Women', lang: 'en', wiki: 'en:Louisa_May_Alcott' },
  { text: 'Ceux qui vivent, ce sont ceux qui luttent.', author: 'Victor Hugo', work: 'Les Châtiments', lang: 'fr', wiki: 'fr:Victor_Hugo' },
  { text: 'En un lugar de la Mancha, de cuyo nombre no quiero acordarme…', author: 'Miguel de Cervantes', work: 'Don Quijote de la Mancha', lang: 'es', wiki: 'es:Miguel_de_Cervantes' },
]
