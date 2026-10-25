# Mentions service names and table-like strings on purpose: the scanner must not read .tf files.
resource "aws_db_instance" "orders" {
  identifier        = "order-service-db"
  engine            = "postgres"
  instance_class    = "db.t4g.medium"
  allocated_storage = 50
  username          = "orders"
  password          = var.orders_db_password
}

resource "aws_elasticache_cluster" "redis" {
  cluster_id      = "cart-service-redis"
  engine          = "redis"
  node_type       = "cache.t4g.small"
  num_cache_nodes = 1
}

variable "orders_db_password" {
  type      = string
  sensitive = true
}
