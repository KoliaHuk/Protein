package protein.extensions

fun String.snake() = this.replace(Regex("([^_A-Z])([A-Z])"), "\$1_\$2").toLowerCase()