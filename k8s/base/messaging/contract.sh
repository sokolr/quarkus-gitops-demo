#!/bin/sh
set -eu

aws_local() { aws --endpoint-url "$AWS_ENDPOINT_URL" "$@"; }
suffix="$(date +%s)-$$"
topic_arn=''
queue_url=''
dlq_url=''
subscription_arn=''
cleanup() {
  [ -z "$subscription_arn" ] || aws_local sns unsubscribe --subscription-arn "$subscription_arn" >/dev/null 2>&1 || true
  [ -z "$topic_arn" ] || aws_local sns delete-topic --topic-arn "$topic_arn" >/dev/null 2>&1 || true
  [ -z "$queue_url" ] || aws_local sqs delete-queue --queue-url "$queue_url" >/dev/null 2>&1 || true
  [ -z "$dlq_url" ] || aws_local sqs delete-queue --queue-url "$dlq_url" >/dev/null 2>&1 || true
}
trap cleanup EXIT

topic_arn=$(aws_local sns create-topic --name "contract-$suffix" --query TopicArn --output text)
dlq_url=$(aws_local sqs create-queue --queue-name "contract-dlq-$suffix" --query QueueUrl --output text)
dlq_arn=$(aws_local sqs get-queue-attributes --queue-url "$dlq_url" \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)
queue_url=$(aws_local sqs create-queue --queue-name "contract-queue-$suffix" --query QueueUrl --output text)
queue_arn=$(aws_local sqs get-queue-attributes --queue-url "$queue_url" \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)
redrive=$(printf '{"deadLetterTargetArn":"%s","maxReceiveCount":"2"}' "$dlq_arn")
attributes=$(printf '{"VisibilityTimeout":"1","RedrivePolicy":"%s"}' \
  "$(printf '%s' "$redrive" | sed 's/"/\\"/g')")
aws_local sqs set-queue-attributes --queue-url "$queue_url" --attributes "$attributes"
subscription_arn=$(aws_local sns subscribe --topic-arn "$topic_arn" --protocol sqs \
  --notification-endpoint "$queue_arn" --return-subscription-arn \
  --query SubscriptionArn --output text)
aws_local sns set-subscription-attributes --subscription-arn "$subscription_arn" \
  --attribute-name RawMessageDelivery --attribute-value true

aws_local sns publish --topic-arn "$topic_arn" --message 'contract-raw-body' >/dev/null
received=$(aws_local sqs receive-message --queue-url "$queue_url" --wait-time-seconds 2 \
  --query 'Messages[0].[Body,ReceiptHandle]' --output text)
body=$(printf '%s\n' "$received" | cut -f 1)
[ "$body" = contract-raw-body ] || { echo "SNS/SQS raw delivery failed: $body" >&2; exit 1; }
receipt=$(printf '%s\n' "$received" | cut -f 2)
[ -n "$receipt" ] || { echo 'Could not read contract receipt handle' >&2; exit 1; }
aws_local sqs delete-message --queue-url "$queue_url" --receipt-handle "$receipt"

aws_local sqs send-message --queue-url "$queue_url" --message-body 'contract-poison' >/dev/null
for attempt in 1 2; do
  body=$(aws_local sqs receive-message --queue-url "$queue_url" --wait-time-seconds 2 \
    --query 'Messages[0].Body' --output text)
  [ "$body" = contract-poison ] || { echo "Poison receive $attempt failed: $body" >&2; exit 1; }
  sleep 2
done
found=false
for attempt in 1 2 3 4 5; do
  aws_local sqs receive-message --queue-url "$queue_url" --wait-time-seconds 1 >/dev/null
  body=$(aws_local sqs receive-message --queue-url "$dlq_url" --wait-time-seconds 1 \
    --query 'Messages[0].Body' --output text)
  if [ "$body" = contract-poison ]; then found=true; break; fi
  sleep 1
done
[ "$found" = true ] || { echo 'Poison message did not reach DLQ' >&2; exit 1; }
echo 'MiniStack contract passed: SNS -> SQS raw delivery and DLQ redrive.'
