UPDATE data_quality_issues
SET severity = 'INFO'
WHERE status = 'OPEN'
  AND issue_code IN (
      'ZERO_UNEXPECTED_COST',
      'RETURN_ZERO_UNEXPECTED_COST'
  )
  AND severity <> 'INFO';
