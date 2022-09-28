%dw 2.0
output application/json

import median from analytics::Statistics
---
median([3, 1, 4])