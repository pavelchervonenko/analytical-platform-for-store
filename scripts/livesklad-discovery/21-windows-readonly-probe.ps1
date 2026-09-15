param(
    [ValidateSet('HistoryDepth', 'SaleDetails', 'EndpointAccess', 'CartProfile')]
    [string]$Mode = 'HistoryDepth',
    [int]$StartYear = 2018,
    [int]$SamplesPerStore = 2,
    [string]$EnvPath = '',
    [string]$PrivateIdentityIndexPath = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($EnvPath)) {
    $EnvPath = Join-Path $PSScriptRoot '..\..\.env'
}
$EnvPath = [System.IO.Path]::GetFullPath($EnvPath)
if (-not (Test-Path -LiteralPath $EnvPath -PathType Leaf)) {
    throw "Environment file is unavailable"
}

function Read-StrictDotEnv {
    param([string]$Path)

    $allowed = @{
        LIVESKLAD_BASE_URL = $true
        LIVESKLAD_LOGIN = $true
        LIVESKLAD_PASSWORD = $true
    }
    $result = @{}
    foreach ($rawLine in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $line = $rawLine.Trim()
        if ($line.Length -eq 0 -or $line.StartsWith('#')) {
            continue
        }
        $match = [regex]::Match($line, '^(?:export\s+)?(?<name>[A-Z_][A-Z0-9_]*)=(?<value>.*)$')
        if (-not $match.Success) {
            throw "Environment file contains an invalid line"
        }
        $name = $match.Groups['name'].Value
        if (-not $allowed.ContainsKey($name)) {
            continue
        }
        if ($result.ContainsKey($name)) {
            throw "Environment file contains a duplicate required variable"
        }
        $value = $match.Groups['value'].Value.Trim()
        if ($value.Length -ge 2) {
            $first = $value.Substring(0, 1)
            $last = $value.Substring($value.Length - 1, 1)
            if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
                $value = $value.Substring(1, $value.Length - 2)
            }
        }
        $result[$name] = $value
    }
    foreach ($required in $allowed.Keys) {
        if (-not $result.ContainsKey($required) -or [string]::IsNullOrWhiteSpace($result[$required])) {
            throw "Environment file is missing a required variable"
        }
    }
    return $result
}

$configuration = Read-StrictDotEnv -Path $EnvPath
$baseUri = [uri]$configuration['LIVESKLAD_BASE_URL']
if ($baseUri.Scheme -ne 'https') {
    throw "LiveSklad base URL must use HTTPS"
}
$baseUrl = $baseUri.AbsoluteUri.TrimEnd('/')

$authBody = @{
    login = $configuration['LIVESKLAD_LOGIN']
    password = $configuration['LIVESKLAD_PASSWORD']
}
$auth = Invoke-RestMethod `
    -Method Post `
    -Uri "$baseUrl/auth" `
    -ContentType 'application/x-www-form-urlencoded' `
    -Body $authBody `
    -TimeoutSec 30
if ([string]::IsNullOrWhiteSpace([string]$auth.token)) {
    throw "LiveSklad authentication returned no token"
}
$headers = @{ Authorization = [string]$auth.token }

function Invoke-LiveSkladGet {
    param(
        [string]$Path,
        [hashtable]$Query = @{}
    )

    if (-not $Path.StartsWith('/')) {
        throw "LiveSklad API path must start with a slash"
    }
    $queryParts = @()
    foreach ($key in ($Query.Keys | Sort-Object)) {
        $encodedKey = [uri]::EscapeDataString([string]$key)
        $encodedValue = [uri]::EscapeDataString([string]$Query[$key])
        $queryParts += "$encodedKey=$encodedValue"
    }
    $uri = "$baseUrl$Path"
    if ($queryParts.Count -gt 0) {
        $uri += '?' + ($queryParts -join '&')
    }
    return Invoke-RestMethod -Method Get -Uri $uri -Headers $headers -TimeoutSec 30
}

function Get-ObservedFields {
    param([object[]]$Items)

    return @(
        $Items |
            Where-Object { $null -ne $_ } |
            ForEach-Object { $_.PSObject.Properties.Name } |
            Sort-Object -Unique
    )
}

$shopsResponse = Invoke-LiveSkladGet -Path '/shops'
$shops = @($shopsResponse.data)
if ($shops.Count -eq 0) {
    throw "LiveSklad returned no shops"
}

if ($Mode -eq 'EndpointAccess') {
    function Get-EndpointProbeResult {
        param(
            [string]$Label,
            [string]$Path,
            [hashtable]$Query = @{}
        )

        try {
            $response = Invoke-LiveSkladGet -Path $Path -Query $Query
            $data = if ($response.PSObject.Properties.Name -contains 'data') { @($response.data) } else { @() }
            return [ordered]@{
                label = $Label
                status = 200
                responseFields = @($response.PSObject.Properties.Name | Sort-Object -Unique)
                dataCount = $data.Count
                reportedTotal = if ($response.PSObject.Properties.Name -contains 'total') { $response.total } else { $null }
            }
        } catch {
            $status = $null
            if ($null -ne $_.Exception.Response -and $null -ne $_.Exception.Response.StatusCode) {
                $status = [int]$_.Exception.Response.StatusCode
            }
            return [ordered]@{
                label = $Label
                status = $status
                responseFields = @()
                dataCount = $null
                reportedTotal = $null
            }
        }
    }

    $firstShop = $shops[0]
    $probes = @(
        Get-EndpointProbeResult -Label 'remain-export-fields' -Path '/company/exports/remains/fields'
        Get-EndpointProbeResult -Label 'product-export-fields' -Path '/company/exports/products/fields'
        Get-EndpointProbeResult -Label 'company-product-remains' -Path '/company/global/products/remains' -Query @{
            page = 1
            pageSize = 1
        }
        Get-EndpointProbeResult -Label 'shop-product-remains-short' -Path "/shops/$($firstShop.id)/products/remains/short" -Query @{
            page = 1
            pageSize = 1
            sort = 'name ASC'
        }
    )

    $latestSales = Invoke-LiveSkladGet -Path "/shops/$($firstShop.id)/sales" -Query @{
        page = 1
        pageSize = 1
        sort = 'date DESC'
    }
    $latestSaleRows = @($latestSales.data)
    if ($latestSaleRows.Count -gt 0) {
        $latestDocument = Invoke-LiveSkladGet -Path "/documents/$($latestSaleRows[0].id)"
        $latestPositions = @($latestDocument.data.positions)
        if ($latestPositions.Count -gt 0) {
            $nomenclatureId = [string]$latestPositions[0].nomenclatureId
            $probes += Get-EndpointProbeResult -Label 'nomenclature-detail' -Path "/nomenclatures/$nomenclatureId"
            $probes += Get-EndpointProbeResult -Label 'nomenclature-history' -Path "/nomenclatures/$nomenclatureId/histories"
        }
    }

    [ordered]@{
        request = [ordered]@{
            base = 'configured-public-api-base'
            method = 'GET'
            outputContainsBusinessRows = $false
        }
        probes = $probes
    } | ConvertTo-Json -Depth 8
    exit 0
}

if ($Mode -eq 'CartProfile') {
    $storeProfiles = @()
    $storeItemIdSets = @()
    for ($shopOffset = 0; $shopOffset -lt $shops.Count; $shopOffset += 1) {
        $shop = $shops[$shopOffset]
        $items = @()
        $page = 1
        $reportedTotal = $null
        do {
            $response = Invoke-LiveSkladGet -Path "/shops/$($shop.id)/carts" -Query @{
                page = $page
                pageSize = 50
            }
            $batch = @($response.data)
            $items += $batch
            $reportedTotal = $response.total
            $page += 1
        } while ($batch.Count -eq 50 -and $page -le 10)
        $itemIdSet = @{}
        foreach ($item in $items) {
            $property = $item.PSObject.Properties['id']
            if ($null -ne $property -and -not [string]::IsNullOrWhiteSpace([string]$property.Value)) {
                $itemIdSet[[string]$property.Value] = $true
            }
        }
        $storeItemIdSets += $itemIdSet
        $dates = @(
            $items |
                ForEach-Object {
                    $property = $_.PSObject.Properties['dateCreate']
                    if ($null -ne $property -and $null -ne $property.Value) {
                        [DateTimeOffset]$property.Value
                    }
                } |
                Sort-Object
        )
        $now = [DateTimeOffset]::UtcNow
        $totalQuantity = [decimal]0
        foreach ($item in $items) {
            $property = $item.PSObject.Properties['count']
            if ($null -ne $property -and $null -ne $property.Value) {
                $totalQuantity += [decimal]$property.Value
            }
        }
        $locationDistribution = @()
        for ($locationOffset = 0; $locationOffset -lt $shops.Count; $locationOffset += 1) {
            $location = $shops[$locationOffset]
            $locationDistribution += [ordered]@{
                shopIndex = $locationOffset + 1
                itemCount = @($items | Where-Object {
                    $property = $_.PSObject.Properties['shopId']
                    $null -ne $property -and [string]$property.Value -eq [string]$location.id
                }).Count
            }
        }
        $storeProfiles += [ordered]@{
            shopIndex = $shopOffset + 1
            sampledCount = $items.Count
            reportedTotal = $reportedTotal
            pagesFetched = $page - 1
            allReportedRecordsFetched = $null -eq $reportedTotal -or $items.Count -eq [int]$reportedTotal
            uniqueItemIdCount = $itemIdSet.Count
            totalQuantity = $totalQuantity
            locationDistribution = $locationDistribution
            itemFields = Get-ObservedFields -Items $items
            withCodeCount = @($items | Where-Object {
                $property = $_.PSObject.Properties['code']
                $null -ne $property -and -not [string]::IsNullOrWhiteSpace([string]$property.Value)
            }).Count
            withNomenclatureIdCount = @($items | Where-Object {
                $property = $_.PSObject.Properties['nomenclatureId']
                $null -ne $property -and -not [string]::IsNullOrWhiteSpace([string]$property.Value)
            }).Count
            withModifyIdCount = @($items | Where-Object {
                $property = $_.PSObject.Properties['modifyId']
                $null -ne $property -and -not [string]::IsNullOrWhiteSpace([string]$property.Value)
            }).Count
            withNameCount = @($items | Where-Object {
                $property = $_.PSObject.Properties['name']
                $null -ne $property -and -not [string]::IsNullOrWhiteSpace([string]$property.Value)
            }).Count
            withQuantityCount = @($items | Where-Object {
                $property = $_.PSObject.Properties['count']
                $null -ne $property -and $null -ne $property.Value
            }).Count
            dateProfile = [ordered]@{
                presentCount = $dates.Count
                earliestObservedDate = if ($dates.Count -gt 0) { $dates[0] } else { $null }
                latestObservedDate = if ($dates.Count -gt 0) { $dates[-1] } else { $null }
                olderThanOneDayCount = @($dates | Where-Object { $_ -lt $now.AddDays(-1) }).Count
                olderThanSevenDaysCount = @($dates | Where-Object { $_ -lt $now.AddDays(-7) }).Count
                olderThanThirtyDaysCount = @($dates | Where-Object { $_ -lt $now.AddDays(-30) }).Count
            }
            lastRateLimitMetadata = [ordered]@{
                remainRequest = $response.remainRequest
                expireDate = $response.expireDate
            }
        }
    }
    $sharedItemIdCount = $null
    if ($storeItemIdSets.Count -eq 2) {
        $sharedItemIdCount = @(
            $storeItemIdSets[0].Keys |
                Where-Object { $storeItemIdSets[1].ContainsKey([string]$_) }
        ).Count
    }
    [ordered]@{
        request = [ordered]@{
            endpoint = '/shops/{id}/carts'
            pageSize = 50
            outputContainsBusinessRows = $false
        }
        stores = $storeProfiles
        crossStoreSharedItemIdCount = $sharedItemIdCount
    } | ConvertTo-Json -Depth 8
    exit 0
}

if ($Mode -eq 'HistoryDepth') {
    $currentYear = [DateTime]::UtcNow.Year
    if ($StartYear -lt 2010 -or $StartYear -gt $currentYear -or ($currentYear - $StartYear) -gt 15) {
        throw "StartYear is outside the safe discovery range"
    }
    $storeResults = @()
    for ($shopOffset = 0; $shopOffset -lt $shops.Count; $shopOffset += 1) {
        $shop = $shops[$shopOffset]
        $ascending = Invoke-LiveSkladGet -Path "/shops/$($shop.id)/sales" -Query @{
            page = 1
            pageSize = 1
            sort = 'date ASC'
        }
        $descending = Invoke-LiveSkladGet -Path "/shops/$($shop.id)/sales" -Query @{
            page = 1
            pageSize = 1
            sort = 'date DESC'
        }
        $yearlyCounts = @()
        $probeCompleted = $true
        for ($year = $StartYear; $year -le $currentYear; $year += 1) {
            $start = [DateTimeOffset]::new($year, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
            $finish = $start.AddYears(1).AddMilliseconds(-1)
            $yearResponse = Invoke-LiveSkladGet -Path "/shops/$($shop.id)/sales" -Query @{
                date = "[$($start.ToUnixTimeMilliseconds()),$($finish.ToUnixTimeMilliseconds())]"
                page = 1
                pageSize = 1
                sort = 'date ASC'
            }
            $yearData = @($yearResponse.data)
            $yearlyCounts += [ordered]@{
                year = $year
                reportedTotal = $yearResponse.total
                firstObservedDate = if ($yearData.Count -gt 0) { $yearData[0].date } else { $null }
            }
            if ($null -ne $yearResponse.remainRequest -and [int]$yearResponse.remainRequest -le 10) {
                $probeCompleted = $false
                break
            }
        }
        $ascendingData = @($ascending.data)
        $descendingData = @($descending.data)
        $storeResults += [ordered]@{
            shopIndex = $shopOffset + 1
            earliestObservedDate = if ($ascendingData.Count -gt 0) { $ascendingData[0].date } else { $null }
            latestObservedDate = if ($descendingData.Count -gt 0) { $descendingData[0].date } else { $null }
            unboundedReportedTotal = $ascending.total
            yearlyCounts = $yearlyCounts
            probeCompleted = $probeCompleted
            lastRateLimitMetadata = [ordered]@{
                remainRequest = $yearResponse.remainRequest
                expireDate = $yearResponse.expireDate
            }
        }
        if (-not $probeCompleted) {
            break
        }
    }
    [ordered]@{
        request = [ordered]@{
            endpoint = '/shops/{id}/sales'
            startYear = $StartYear
            endYear = $currentYear
            pageSize = 1
            outputContainsBusinessRows = $false
        }
        shopCount = $shops.Count
        stores = $storeResults
    } | ConvertTo-Json -Depth 10
    exit 0
}

if ($SamplesPerStore -lt 1 -or $SamplesPerStore -gt 5) {
    throw "SamplesPerStore must be between 1 and 5"
}
$documents = @()
$positions = @()
$batches = @()
$lastResponse = $null
$catalogCodes = @{}
$catalogBarcodes = @{}
if (-not [string]::IsNullOrWhiteSpace($PrivateIdentityIndexPath)) {
    if (-not (Test-Path -LiteralPath $PrivateIdentityIndexPath -PathType Leaf)) {
        throw "Private identity index is unavailable"
    }
    $identityIndex = Get-Content -LiteralPath $PrivateIdentityIndexPath -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($value in @($identityIndex.codes)) {
        $catalogCodes[[string]$value] = $true
    }
    foreach ($value in @($identityIndex.barcodes)) {
        $catalogBarcodes[[string]$value] = $true
    }
}
$end = [DateTimeOffset]::UtcNow
$start = $end.AddDays(-7)
for ($shopOffset = 0; $shopOffset -lt $shops.Count; $shopOffset += 1) {
    $shop = $shops[$shopOffset]
    $sales = Invoke-LiveSkladGet -Path "/shops/$($shop.id)/sales" -Query @{
        date = "[$($start.ToUnixTimeMilliseconds()),$($end.ToUnixTimeMilliseconds())]"
        page = 1
        pageSize = $SamplesPerStore
        sort = 'date DESC'
    }
    foreach ($sale in @($sales.data)) {
        $lastResponse = Invoke-LiveSkladGet -Path "/documents/$($sale.id)"
        $document = $lastResponse.data
        $documents += $document
        foreach ($position in @($document.positions)) {
            $positions += $position
            foreach ($batch in @($position.batches)) {
                $batches += $batch
            }
        }
    }
}
$nomenclatureGroups = @(
    $positions |
        Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_.nomenclatureId) } |
        Group-Object { [string]$_.nomenclatureId }
)
$modifyGroups = @(
    $positions |
        Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_.modifyId) } |
        Group-Object { [string]$_.modifyId }
)

[ordered]@{
    request = [ordered]@{
        detailEndpoint = '/documents/{id}'
        periodDays = 7
        samplesPerStore = $SamplesPerStore
        outputContainsBusinessRows = $false
    }
    selectedDocumentCount = $documents.Count
    documentFields = Get-ObservedFields -Items $documents
    positionCount = $positions.Count
    positionFields = Get-ObservedFields -Items $positions
    positionsWithNomenclatureId = @($positions | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_.nomenclatureId) }).Count
    uniqueNomenclatureIdCount = @($positions | ForEach-Object { $_.nomenclatureId } | Where-Object { $_ } | Sort-Object -Unique).Count
    positionsWithCode = @($positions | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_.code) }).Count
    uniqueCodeCount = @($positions | ForEach-Object { $_.code } | Where-Object { $_ } | Sort-Object -Unique).Count
    codeEqualsNomenclatureIdCount = @(
        $positions |
            Where-Object {
                -not [string]::IsNullOrWhiteSpace([string]$_.code) -and
                [string]$_.code -eq [string]$_.nomenclatureId
            }
    ).Count
    positionsWithModifyId = @($positions | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_.modifyId) }).Count
    uniqueModifyIdCount = @($positions | ForEach-Object { $_.modifyId } | Where-Object { $_ } | Sort-Object -Unique).Count
    nomenclatureIdsWithMultipleModifyIds = @(
        $nomenclatureGroups |
            Where-Object {
                @($_.Group | ForEach-Object { $_.modifyId } | Where-Object { $_ } | Sort-Object -Unique).Count -gt 1
            }
    ).Count
    nomenclatureIdsWithMultipleCodes = @(
        $nomenclatureGroups |
            Where-Object {
                @($_.Group | ForEach-Object { $_.code } | Where-Object { $_ } | Sort-Object -Unique).Count -gt 1
            }
    ).Count
    modifyIdsWithMultipleCodes = @(
        $modifyGroups |
            Where-Object {
                @($_.Group | ForEach-Object { $_.code } | Where-Object { $_ } | Sort-Object -Unique).Count -gt 1
            }
    ).Count
    workPositionCount = @($positions | Where-Object { $_.isWork -eq $true }).Count
    productPositionCount = @($positions | Where-Object { $_.isWork -ne $true }).Count
    catalogIdentityChecks = if ($catalogCodes.Count -gt 0) {
        [ordered]@{
            codeInCatalogCodeCount = @($positions | Where-Object { $catalogCodes.ContainsKey([string]$_.code) }).Count
            productCodeInCatalogCodeCount = @(
                $positions |
                    Where-Object { $_.isWork -ne $true -and $catalogCodes.ContainsKey([string]$_.code) }
            ).Count
            workCodeInCatalogCodeCount = @(
                $positions |
                    Where-Object { $_.isWork -eq $true -and $catalogCodes.ContainsKey([string]$_.code) }
            ).Count
            nomenclatureIdInCatalogCodeCount = @($positions | Where-Object { $catalogCodes.ContainsKey([string]$_.nomenclatureId) }).Count
            modifyIdInCatalogCodeCount = @($positions | Where-Object { $catalogCodes.ContainsKey([string]$_.modifyId) }).Count
            codeInCatalogBarcodeCount = @($positions | Where-Object { $catalogBarcodes.ContainsKey([string]$_.code) }).Count
            nomenclatureIdInCatalogBarcodeCount = @($positions | Where-Object { $catalogBarcodes.ContainsKey([string]$_.nomenclatureId) }).Count
            modifyIdInCatalogBarcodeCount = @($positions | Where-Object { $catalogBarcodes.ContainsKey([string]$_.modifyId) }).Count
        }
    } else {
        $null
    }
    batchCount = $batches.Count
    batchFields = Get-ObservedFields -Items $batches
    uniqueBatchIdCount = @($batches | ForEach-Object { $_.batchId } | Where-Object { $_ } | Sort-Object -Unique).Count
    uniqueBatchStoreIdCount = @($batches | ForEach-Object { $_.storeId } | Where-Object { $_ } | Sort-Object -Unique).Count
    lastRateLimitMetadata = [ordered]@{
        remainRequest = if ($null -ne $lastResponse) { $lastResponse.remainRequest } else { $shopsResponse.remainRequest }
        expireDate = if ($null -ne $lastResponse) { $lastResponse.expireDate } else { $shopsResponse.expireDate }
    }
} | ConvertTo-Json -Depth 8
