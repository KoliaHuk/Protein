package protein.extensions

fun String.snakeCase() {
    this.decapitalize().replace(Regex("(?<=.)([A-Z])"), "_\\l\$0")
}