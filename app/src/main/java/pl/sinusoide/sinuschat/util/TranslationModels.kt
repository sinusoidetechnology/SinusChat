package pl.sinusoide.sinuschat.util

object TranslationModels {

    const val BASE_URL = "https://firefox-settings-attachments.cdn.mozilla.net/"

    class Part(val name: String, val sha256: String, val size: Long, val path: String) {
        val download: Long get() = size
    }

    class Pair(val from: String, val to: String, val model: Part, val vocab: Part, val lex: Part?, val trgVocab: Part? = null) {
        val code get() = from + to
        val parts get() = listOfNotNull(model, vocab, trgVocab, lex)
    }

    val PAIRS: List<Pair> = listOf(
        Pair("pl", "en",
            Part("model.plen.intgemm.alphas.bin", "06dbab3baa3bbceab746bc6a3bba807878f38536382c03acddf59d3edf3b5d94", 31561787L, "main-workspace/translations-models/dd8a7ab8-b12f-4ad4-8827-0922510df232.bin"),
            Part("vocab.plen.spm", "2000f182efa8ce5f69bc3f9e574666b5bc2c0af684eedd32f69d15aa9021ea85", 821612L, "main-workspace/translations-models/dd8a3019-bd2f-405e-b95d-0cce7a29be2d.spm"),
            Part("lex.50.50.plen.s2t.bin", "b295dfefa7022a62e105276e8c39fdfd67bffe588f4fd78583edd4e04ee0cb3b", 4964208L, "main-workspace/translations-models/9a072be8-d900-4062-a2ab-c53c8eb8e619.bin")),
        Pair("en", "pl",
            Part("model.enpl.intgemm.alphas.bin", "b0b59716bcc8f46b8dc6ab5c32a2732acaad42a5a6d9ae9b472526a1bb0d71d4", 31561787L, "main-workspace/translations-models/640bd7ef-a977-417f-903c-00b5217cb7de.bin"),
            Part("vocab.enpl.spm", "2000f182efa8ce5f69bc3f9e574666b5bc2c0af684eedd32f69d15aa9021ea85", 821612L, "main-workspace/translations-models/dd8a3019-bd2f-405e-b95d-0cce7a29be2d.spm"),
            Part("lex.50.50.enpl.s2t.bin", "d7d7b364673f633763c9f8f2e8ee9c7a184e92f4086e8b84abe870a2a8dbe5d5", 3540052L, "main-workspace/translations-models/12451e17-4901-4327-b78d-903e9ff7529d.bin")),
        Pair("cs", "en",
            Part("model.csen.intgemm.alphas.bin", "c815273b31074492eaab0ccd78d33d7ac16f94088707ab2492abaedcb9ee0b75", 31561787L, "main-workspace/translations-models/23af171d-0de1-4d96-a495-c9aa5a0b503d.bin"),
            Part("vocab.csen.spm", "ee4b8ce15c8e1237c5f7a2d04741341beb0a5d5c09820f7ec08f13ee2b212755", 815154L, "main-workspace/translations-models/008ef5e3-4ba0-4d97-9dd6-5898c7348e28.spm"),
            Part("lex.50.50.csen.s2t.bin", "eba6bd9575251525f1941640ea0b6527a06e72a46421579eafea04d635ee20a8", 4990588L, "main-workspace/translations-models/5041825d-9867-42aa-a7ed-633b23912956.bin")),
        Pair("en", "cs",
            Part("model.encs.intgemm.alphas.bin", "1d7855f91c3e2b8ef79fbea62707d46ceb8a1ad01834ef6dd500f32a7006cc2c", 31561787L, "main-workspace/translations-models/6dc79644-4994-4816-8d12-418b11df69ff.bin"),
            Part("vocab.encs.spm", "ee4b8ce15c8e1237c5f7a2d04741341beb0a5d5c09820f7ec08f13ee2b212755", 815154L, "main-workspace/translations-models/008ef5e3-4ba0-4d97-9dd6-5898c7348e28.spm"),
            Part("lex.50.50.encs.s2t.bin", "699b9b59fe3acbcddf831380024b2e139f2cbe225dce96d59ade2b159318f58b", 3572436L, "main-workspace/translations-models/0b6ddbc2-b4cc-45a9-b2db-fe9369ad2064.bin")),
        Pair("uk", "en",
            Part("model.uken.intgemm.alphas.bin", "2e7b8bc9d2569d8b4ae244e964cdc343a0aa3735cc83ecb664be84a37cb45f48", 17141051L, "main-workspace/translations-models/31e03e41-af47-4de2-9420-f6fd206e0114.bin"),
            Part("vocab.uken.spm", "8b51480dfc4ca2d7a08d2bfdaa9e57353ca97e6cf3d5f9aba4c0179d3665bfc0", 886410L, "main-workspace/translations-models/3155c3f0-b486-40f3-bfdd-1b3b734cc171.spm"),
            Part("lex.50.50.uken.s2t.bin", "32b5e0df493671a223cb5cb32430181cbb7fe6b5562c91dbc3d35410d37e5ff6", 3914120L, "main-workspace/translations-models/3e01a41b-9384-4bfc-98b9-031df4f9ee5e.bin")),
        Pair("en", "uk",
            Part("model.enuk.intgemm.alphas.bin", "ed8bb4a811e1c45a259e91052080d25e5c8b4e2df0e59cb1521c04a57855ef97", 31561787L, "main-workspace/translations-models/6c69578a-1f77-44ce-993d-4d37c81e081e.bin"),
            Part("vocab.enuk.spm", "4dd6a91feff71e745395dcc0d52f885848ca0d843d5f70628ca51b3be1b70b45", 922077L, "main-workspace/translations-models/ffea5811-fcfe-486e-8812-6284bd33ee3a.spm"),
            Part("lex.50.50.enuk.s2t.bin", "dbfd4f8dc95da9c575164f7fc95ef00a2fb5f7d9f51b656559f99eeebdb57c87", 3054364L, "main-workspace/translations-models/e7b2fd05-abf2-4e30-a612-59bce8a6702c.bin")),
        Pair("de", "en",
            Part("model.deen.intgemm.alphas.bin", "3e6f7c2c2425d10824797270b382bee718ff34af2cab9308841c82ca46dc6f20", 31561787L, "main-workspace/translations-models/f44b1b1b-9df6-4ece-971e-0e5ce96fae54.bin"),
            Part("vocab.deen.spm", "69f730becafa48e3bb2c244eab66456877c08959a02f2bd5519b5a3088b62f9c", 810073L, "main-workspace/translations-models/261225ea-5a52-455b-981c-7d09c6e6da3c.spm"),
            Part("lex.50.50.deen.s2t.bin", "113b98460468360cca68c042e1cddf49c4e1931cbb975ed04349c9a3bd607010", 4945796L, "main-workspace/translations-models/d0e4efcb-6145-43db-a69e-568904cc2925.bin")),
        Pair("zh", "en",
            Part("model.zhten.intgemm.alphas.bin", "0aee91790894458f5d367551f6edcd4c9cb97852c34f221bcbf9f4701ebcf0cd", 43849787L, "main-workspace/translations-models/20260522194230--6ca6db97-6bce-4110-981f-350e15965675--8323efd2-43e9-4886-9f60-35870d1744ea.bin"),
            Part("srcvocab.zhten.spm", "5cc6a76611dbf86219f109141533606b15ecb34eee83673bb86b2c16b14734db", 769669L, "main-workspace/translations-models/20260522194213--ad6a9ed2-06ed-4c72-b6ba-aa4492bd582f--72fab356-41da-4bd6-a22c-aa5456ba3ea1.spm"),
            Part("lex.50.50.zhten.s2t.bin", "aa7daf6cfc85c0cd2c10e2944d66f6da55497c9c6408789f3adfded4074c2fb1", 6385944L, "main-workspace/translations-models/20260522194215--6c386193-432a-422f-be5f-3cad0977e52b--a52e4a54-5f80-43ce-b873-1bc792ec1483.bin"),
            Part("trgvocab.zhten.spm", "7bf002db37c10d3b114cc5588d7fdcb16c57d0fd1e2c34354c22cc9f0b6c3c29", 812572L, "main-workspace/translations-models/20260522194217--5e1d7df1-80a8-4142-ae9d-0ff51b8c2fbc--5e39a274-9805-4d87-a950-39b3d95aac93.spm")),
        Pair("en", "zh",
            Part("model.enzht.intgemm.alphas.bin", "559ab90d723a58c1f1e2ab7cc12137bc667af5ba3e325e3eb30b5cdc930db520", 43849787L, "main-workspace/translations-models/20260522194155--76d2a34e-7ef2-4829-ac1a-a1319b852dc5--72be7771-db83-4218-bec5-44f021cbf00b.bin"),
            Part("srcvocab.enzht.spm", "2266df70492162a249ab1c0154f929bd6098b246544c666c1a0d5a24dde7d2ea", 803694L, "main-workspace/translations-models/20260522194144--b93467f8-2c0b-43f4-a662-19cae5c7f899--ae44a945-6ed0-44d2-88ac-d4b9c25f6c3a.spm"),
            Part("lex.50.50.enzht.s2t.bin", "d891404d1436a7334df12539fe30a26f9e9f2b80bd42fdb8b5f8849e8a1e942b", 4057188L, "main-workspace/translations-models/20260522194159--13a8d548-8d6a-46f6-ab8d-ea19098b7fe8--6cf4bdcb-dbe5-4e2d-af40-a02b440fdcc9.bin"),
            Part("trgvocab.enzht.spm", "22b91a4436d70b91ab8777c677252ab5fae2bc284d71f977df5206c110e3444c", 751671L, "main-workspace/translations-models/20260522194157--52f509a0-ac8b-48bf-ba9f-0c61a064a00a--85418cc0-208e-440b-ac6f-c56d242ca430.spm")),
        Pair("en", "de",
            Part("model.ende.intgemm.alphas.bin", "8df29d9494d19f47fd5d97c6a73474c6f657e9f81c1a607c431d02befdf3810f", 31561787L, "main-workspace/translations-models/23db71e7-b6d9-45eb-a47d-0290d7d8ef63.bin"),
            Part("vocab.ende.spm", "69f730becafa48e3bb2c244eab66456877c08959a02f2bd5519b5a3088b62f9c", 810073L, "main-workspace/translations-models/261225ea-5a52-455b-981c-7d09c6e6da3c.spm"),
            Part("lex.50.50.ende.s2t.bin", "7ed39f1cffbd68a27ddf05bbfe068de2060f1d7e69f1a20e27ae923551dd7393", 4347672L, "main-workspace/translations-models/bc072b1a-7749-43f7-9fe0-34a6dff10c4a.bin"))
    )

    fun pair(from: String, to: String): Pair? = PAIRS.firstOrNull { it.from == from && it.to == to }

    fun pairsFor(lang: String): List<Pair> = PAIRS.filter { it.from == lang || it.to == lang }
}
