#!/bin/sh
set -eu

aws_local() { aws --endpoint-url "$AWS_ENDPOINT_URL" "$@"; }

topic_arn=$(aws_local sns create-topic --name service-b-events --query TopicArn --output text)
dlq_url=$(aws_local sqs create-queue --queue-name service-a-events-dlq \
  --query QueueUrl --output text)
dlq_arn=$(aws_local sqs get-queue-attributes --queue-url "$dlq_url" \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)
queue_url=$(aws_local sqs create-queue --queue-name service-a-events \
  --query QueueUrl --output text)
queue_arn=$(aws_local sqs get-queue-attributes --queue-url "$queue_url" \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

redrive=$(printf '{"deadLetterTargetArn":"%s","maxReceiveCount":"3"}' "$dlq_arn")
attributes=$(printf '{"VisibilityTimeout":"30","ReceiveMessageWaitTimeSeconds":"2","RedrivePolicy":"%s"}' \
  "$(printf '%s' "$redrive" | sed 's/"/\\"/g')")
aws_local sqs set-queue-attributes --queue-url "$queue_url" --attributes "$attributes"

policy=$(printf '{"Version":"2012-10-17","Statement":[{"Sid":"AllowTopicDelivery","Effect":"Allow","Principal":{"Service":"sns.amazonaws.com"},"Action":"sqs:SendMessage","Resource":"%s","Condition":{"ArnEquals":{"aws:SourceArn":"%s"}}}]}' \
  "$queue_arn" "$topic_arn")
policy_attributes=$(printf '{"Policy":"%s"}' "$(printf '%s' "$policy" | sed 's/"/\\"/g')")
aws_local sqs set-queue-attributes --queue-url "$queue_url" --attributes "$policy_attributes"

subscription_arn=$(aws_local sns subscribe --topic-arn "$topic_arn" --protocol sqs \
  --notification-endpoint "$queue_arn" --return-subscription-arn \
  --query SubscriptionArn --output text)
aws_local sns set-subscription-attributes --subscription-arn "$subscription_arn" \
  --attribute-name RawMessageDelivery --attribute-value true

echo "Provisioned topic=$topic_arn queue=$queue_url dlq=$dlq_url raw_subscription=$subscription_arn"
/bin/sh /scripts/contract.sh
