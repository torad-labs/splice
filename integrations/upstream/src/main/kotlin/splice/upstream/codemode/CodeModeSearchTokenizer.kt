// PORT-OF: bm25 2.3.2 default_tokenizer.rs, the tokenizer used by Codex rust-v0.164.0.
package splice.upstream.codemode

import splice.upstream.codemode.search.SearchDeunicode
import splice.upstream.codemode.search.SearchEnglishStemmer
import splice.upstream.codemode.search.SearchWords

/** Codex's default English tokenizer: transliterate, lowercase, split, remove stopwords, then stem. */
public object CodeModeSearchTokenizer {
    private val stopwords: Set<String> = checkNotNull(
        javaClass.getResourceAsStream("search-stopwords.txt"),
    ) { "Missing CodeMode search stopwords" }.bufferedReader(Charsets.UTF_8).use { it.readLines().toSet() }

    public fun tokens(text: String): List<String> = SearchWords.split(SearchDeunicode.normalize(text).lowercase())
        .filterNot { it in stopwords }
        .map(SearchEnglishStemmer::stem)
}
