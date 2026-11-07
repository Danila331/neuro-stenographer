// Brute-force dot-product scan, single thread. Proxy for "Room + FloatArray in Kotlin" (scalar JIT loop) vs. SIMD.
// cc -O2 -fno-vectorize -fno-slp-vectorize scripts/bf_scalar.c -o /tmp/bf_scalar && /tmp/bf_scalar 100000 384
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <stdint.h>
static double now(){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec*1e3+t.tv_nsec/1e6;}
int main(int argc,char**argv){
  int n=atoi(argv[1]),d=atoi(argv[2]);
  float*x=malloc((size_t)n*d*4),*q=malloc(d*4);int8_t*x8=malloc((size_t)n*d);int8_t*q8=malloc(d);
  for(size_t i=0;i<(size_t)n*d;i++){x[i]=(rand()%2001-1000)/1000.f;x8[i]=rand()%255-127;}
  for(int j=0;j<d;j++){q[j]=(rand()%2001-1000)/1000.f;q8[j]=rand()%255-127;}
  volatile float best=-1e9;volatile int bi=0;
  for(int rep=0;rep<2;rep++){
    double t0=now();
    for(int r=0;r<5;r++){float b=-1e9;int bb=0;for(int i=0;i<n;i++){float s=0;const float*p=x+(size_t)i*d;for(int j=0;j<d;j++)s+=p[j]*q[j];if(s>b){b=s;bb=i;}}best=b;bi=bb;}
    double t1=now();
    for(int r=0;r<5;r++){int b=-1<<30,bb=0;for(int i=0;i<n;i++){int s=0;const int8_t*p=x8+(size_t)i*d;for(int j=0;j<d;j++)s+=p[j]*q8[j];if(s>b){b=s;bb=i;}}best=b;bi=bb;}
    double t2=now();
    if(rep)printf("n=%d d=%d  f32: %.1f ms/query   int8: %.1f ms/query\n",n,d,(t1-t0)/5,(t2-t1)/5);
  }
  return 0;}
