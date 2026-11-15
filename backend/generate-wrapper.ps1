$ErrorActionPreference = "Stop"
$BackendDir = $PSScriptRoot
$GradleImage = "gradle:9.9.1-jdk25"
$DistributionSha256 = "d8a18a7a841dd5df825d35a685fad6f2d7f12ac0c280e57b67c27b4a520ba228"
$WrapperJarSha256 = "621008afc4f5d59357fcf06ffa644dd5aade5e091bdc363742710445f73cdaf8"

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker is required. Do not generate the wrapper using a globally installed Gradle version."
}

docker run --rm -v "${BackendDir}:/workspace" -w /workspace $GradleImage `
    gradle --no-daemon wrapper --gradle-version 9.9.1 --distribution-type bin `
    --gradle-distribution-sha256-sum $DistributionSha256
if ($LASTEXITCODE -ne 0) { throw "Gradle Wrapper generation failed." }

$WrapperJar = Join-Path $BackendDir "gradle/wrapper/gradle-wrapper.jar"
$Actual = (Get-FileHash -Algorithm SHA256 $WrapperJar).Hash.ToLowerInvariant()
if ($Actual -ne $WrapperJarSha256) {
    throw "Unexpected Gradle Wrapper JAR checksum: $Actual"
}

Write-Host "Gradle 9.9.1 wrapper generated and checksum verified."
Write-Host "Next: cd backend; .\gradlew.bat --version; .\gradlew.bat clean check bootJar"
