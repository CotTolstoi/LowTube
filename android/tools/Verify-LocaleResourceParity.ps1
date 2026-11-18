param(
    [string]$ResourceRoot = (Join-Path $PSScriptRoot '..\app\src\main\res')
)

$ErrorActionPreference = 'Stop'
$resolvedRoot = (Resolve-Path -LiteralPath $ResourceRoot).Path

function Get-LocaleResourceKeys([string]$Folder) {
    $resolvedFolder = Join-Path $resolvedRoot $Folder
    if (-not (Test-Path -LiteralPath $resolvedFolder -PathType Container)) {
        throw "Missing Android resource folder: $resolvedFolder"
    }

    $keys = New-Object 'System.Collections.Generic.List[string]'
    foreach ($file in Get-ChildItem -LiteralPath $resolvedFolder -Filter 'strings.xml' -File) {
        [xml]$document = Get-Content -LiteralPath $file.FullName -Raw
        foreach ($resource in $document.DocumentElement.ChildNodes) {
            if ($resource.NodeType -ne [System.Xml.XmlNodeType]::Element) { continue }
            if (-not $resource.HasAttribute('name')) { continue }

            $keys.Add("$($resource.LocalName):$($resource.GetAttribute('name'))")
        }
    }

    return $keys | Sort-Object -Unique
}

$baseline = @(Get-LocaleResourceKeys 'values')
if ($baseline.Count -eq 0) {
    throw "No named resources found under $resolvedRoot\values"
}

$hasDifferences = $false
Write-Output "EN resources: $($baseline.Count)"
foreach ($locale in @(@('values-uk', 'UK'), @('values-ru', 'RU'))) {
    $folder = $locale[0]
    $label = $locale[1]
    $localized = @(Get-LocaleResourceKeys $folder)
    $difference = @(Compare-Object -ReferenceObject $baseline -DifferenceObject $localized)
    $missing = @($difference | Where-Object SideIndicator -eq '<=' | ForEach-Object InputObject)
    $extra = @($difference | Where-Object SideIndicator -eq '=>' | ForEach-Object InputObject)

    Write-Output "$label resources: $($localized.Count); missing: $($missing.Count); extra: $($extra.Count)"
    if ($missing.Count -gt 0) {
        $hasDifferences = $true
        $missing | ForEach-Object { Write-Output "  MISSING $_" }
    }
    if ($extra.Count -gt 0) {
        $hasDifferences = $true
        $extra | ForEach-Object { Write-Output "  EXTRA $_" }
    }
}

if ($hasDifferences) {
    exit 1
}
