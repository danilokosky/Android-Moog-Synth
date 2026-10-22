cmake_minimum_required(VERSION 3.22.1)
project("moogsynth")

# Encontra a biblioteca Google Oboe que importamos no Gradle
find_package(oboe REQUIRED CONFIG)

# Define o nome da nossa biblioteca C++ e qual arquivo será compilado
add_library(native-lib SHARED native-lib.cpp)

# Conecta o nosso código com o Oboe e com o sistema de logs do Android
target_link_libraries(native-lib
        oboe::oboe
        log
        android)
