plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.moogsynth.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.moogsynth.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        
        // Configuração do compilador C++
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17 -O3" // -O3 ativa a otimização máxima de performance
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    
    buildFeatures {
        compose = true
        prefab = true // ESSENCIAL: Permite importar bibliotecas C++ pré-compiladas (como o Oboe)
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    
    // Aponta onde estará o nosso roteiro de compilação C++
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    
    // Biblioteca oficial do Google para Áudio de Ultra Baixa Latência em C++
    implementation("com.google.oboe:oboe:1.8.1")
}
