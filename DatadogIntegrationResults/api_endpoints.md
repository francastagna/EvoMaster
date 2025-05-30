# Datadog API Endpoints Used in Integration

## Metrics API
- **Endpoint**: `/api/v2/metrics/query`
- **Purpose**: Query error rates and response times for the SUT
- **Query Example**:
  ```
  GET /api/v2/metrics/query?query=sum:trace.servlet.request.errors{service:features-service}.as_rate()&from=1716661478&to=1716662378
  ```
- **Response Example**:
  ```json
  {
    "data": {
      "attributes": {
        "series": [
          {
            "values": [[1716661478, 0.05], [1716661778, 0.02], [1716662078, 0.01], [1716662378, 0.03]]
          }
        ]
      }
    }
  }
  ```
- **Documentation**: https://docs.datadoghq.com/api/latest/metrics/

## Security Monitoring API
- **Endpoint**: `/api/v2/security_monitoring/signals`
- **Purpose**: Retrieve security signals related to the SUT
- **Query Example**:
  ```
  GET /api/v2/security_monitoring/signals?filter[query]=service:features-service&filter[from]=1716661478000&filter[to]=1716662378000
  ```
- **Response Example**:
  ```json
  {
    "data": [
      {
        "id": "AQAAAYxYcGVmZWF0dXJlcy1zZXJ2aWNl",
        "type": "security_signal",
        "attributes": {
          "message": "Potential SQL injection detected",
          "status": "open",
          "severity": "high"
        }
      }
    ],
    "meta": {
      "page": {
        "total_count": 1
      }
    }
  }
  ```
- **Documentation**: https://docs.datadoghq.com/api/latest/security-monitoring/

## Posture Management API
- **Endpoint**: `/api/v2/posture_management/findings`
- **Purpose**: Retrieve critical security findings
- **Query Example**:
  ```
  GET /api/v2/posture_management/findings?filter[status]=critical&filter[discovery_timestamp]=>1716661478000
  ```
- **Response Example**:
  ```json
  {
    "data": [
      {
        "id": "f-abc123",
        "type": "finding",
        "attributes": {
          "resource": "features-service-api",
          "status": "critical",
          "title": "Insecure API endpoint detected",
          "evaluation_message": "API endpoint allows unauthenticated access"
        }
      }
    ],
    "meta": {
      "page": {
        "total_count": 1
      }
    }
  }
  ```
- **Documentation**: https://docs.datadoghq.com/api/latest/cloud-security-posture/
