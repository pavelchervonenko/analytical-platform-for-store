param(
    [ValidateSet('AssetList', 'EndpointHints', 'ApiBaseHints', 'EndpointContexts', 'HttpCallHints')]
    [string]$Mode = 'AssetList'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$pageUri = [uri]'https://my.livesklad.com/'
$page = Invoke-WebRequest -UseBasicParsing -Uri $pageUri -TimeoutSec 30
$assetUris = @(
    [regex]::Matches($page.Content, '<script[^>]+src=["''](?<src>[^"'']+)') |
        ForEach-Object { [uri]::new($pageUri, $_.Groups['src'].Value).AbsoluteUri } |
        Sort-Object -Unique
)

if ($Mode -eq 'AssetList') {
    [ordered]@{
        page = $pageUri.AbsoluteUri
        scriptAssetCount = $assetUris.Count
        scriptAssets = $assetUris
    } | ConvertTo-Json -Depth 4
    exit 0
}

$hintPattern = [regex]::new(
    '(?i)(?:https://[^"''\s]+)?/[A-Za-z0-9_{}?=&./:\-]{0,120}' +
    '(?:stock|warehouse|storehouse|nomencl|product|receipt|arrival|income|balance|inventory|batch|transfer)' +
    '[A-Za-z0-9_{}?=&./:\-]{0,120}'
)
$hints = @()
$assetContents = @()
$scannedAssets = 0
foreach ($assetUri in $assetUris) {
    $asset = Invoke-WebRequest -UseBasicParsing -Uri $assetUri -TimeoutSec 60
    $scannedAssets += 1
    $assetContents += [ordered]@{
        uri = $assetUri
        content = $asset.Content
    }
    $hints += @(
        $hintPattern.Matches($asset.Content) |
            ForEach-Object { $_.Value.TrimEnd('.', ',', ':', ';') }
    )
}

if ($Mode -eq 'ApiBaseHints') {
    $urlPattern = [regex]::new('https?://[A-Za-z0-9._:\-/]+')
    $urls = @(
        $assetContents |
            ForEach-Object { $urlPattern.Matches($_.content) } |
            ForEach-Object { $_.Value.TrimEnd('.', ',', ':', ';', '/') } |
            Where-Object { $_ -match '(?i)livesklad' } |
            Sort-Object -Unique
    )
    [ordered]@{
        page = $pageUri.AbsoluteUri
        scannedAssetCount = $scannedAssets
        liveskladUrlHints = $urls
    } | ConvertTo-Json -Depth 5
    exit 0
}

if ($Mode -eq 'EndpointContexts') {
    $targets = @(
        '/company/exports/products',
        '/company/exports/remains',
        '/company/global/products/remains',
        '/nomenclatures',
        '/products/remains/short',
        '/inventory/shop-remains',
        '/product-batches',
        '/product-history'
    )
    $contexts = @()
    foreach ($asset in $assetContents) {
        foreach ($target in $targets) {
            $occurrence = 0
            foreach ($match in [regex]::Matches($asset.content, [regex]::Escape($target))) {
                $occurrence += 1
                if ($occurrence -gt 4) {
                    break
                }
                $start = [Math]::Max(0, $match.Index - 220)
                $length = [Math]::Min(600, $asset.content.Length - $start)
                $contexts += [ordered]@{
                    target = $target
                    occurrence = $occurrence
                    context = $asset.content.Substring($start, $length)
                }
            }
        }
    }
    [ordered]@{
        page = $pageUri.AbsoluteUri
        scannedAssetCount = $scannedAssets
        contexts = $contexts
    } | ConvertTo-Json -Depth 6
    exit 0
}

if ($Mode -eq 'HttpCallHints') {
    $callPattern = [regex]::new(
        'Ve\.(?<method>get|post|put|patch|delete)\((?<args>.{0,500}?)\)',
        [System.Text.RegularExpressions.RegexOptions]::Singleline
    )
    $calls = @()
    foreach ($asset in $assetContents) {
        foreach ($match in $callPattern.Matches($asset.content)) {
            $arguments = $match.Groups['args'].Value
            if ($arguments -notmatch '(?i)stock|warehouse|storehouse|nomencl|product|receipt|arrival|income|balance|inventory|batch|transfer|exports/remains') {
                continue
            }
            $calls += [ordered]@{
                method = $match.Groups['method'].Value.ToUpperInvariant()
                arguments = $arguments
            }
        }
    }
    [ordered]@{
        page = $pageUri.AbsoluteUri
        scannedAssetCount = $scannedAssets
        httpCallHints = @($calls | Sort-Object method, arguments -Unique)
    } | ConvertTo-Json -Depth 6
    exit 0
}

[ordered]@{
    page = $pageUri.AbsoluteUri
    scriptAssetCount = $assetUris.Count
    scannedAssetCount = $scannedAssets
    endpointHints = @($hints | Sort-Object -Unique)
} | ConvertTo-Json -Depth 5
