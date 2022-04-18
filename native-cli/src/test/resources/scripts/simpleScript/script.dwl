%dw 2.0

input in0 application/json
input in1 application/json

output application/json
---
{
   a: in0.hi,
   b: in1.bla
}