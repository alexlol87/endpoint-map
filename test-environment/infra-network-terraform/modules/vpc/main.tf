variable "cidr_block" { type = string }
variable "name" { type = string }

resource "aws_vpc" "this" {
  cidr_block           = var.cidr_block
  enable_dns_hostnames = true
  tags = { Name = var.name }
}

output "vpc_id" {
  value = aws_vpc.this.id
}
